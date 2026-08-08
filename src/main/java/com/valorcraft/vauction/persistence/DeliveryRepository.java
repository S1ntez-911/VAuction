package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.item.ItemSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий deliveries (выдача предметов). dedupe_key UNIQUE защищает
 * от повторной выдачи; переходы состояния — через optimistic lock.
 */
public final class DeliveryRepository {

    private static final String COLUMNS = "delivery_id, dedupe_key, player_uuid, listing_id, operation_id, "
            + "delivery_type, state, item_blob, item_codec_version, item_hash, item_registry_id, "
            + "item_display_name, item_search_name, quantity, created_at, claimable_at, claim_started_at, "
            + "claimed_at, claim_token, last_error, version";

    public long insert(Connection c, AuctionDelivery delivery) {
        String sql = "INSERT INTO auction_deliveries (dedupe_key, player_uuid, listing_id, operation_id, "
                + "delivery_type, state, item_blob, item_codec_version, item_hash, item_registry_id, "
                + "item_display_name, item_search_name, quantity, created_at, claimable_at, claim_started_at, "
                + "claimed_at, claim_token, last_error, version) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement ps = c.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, delivery.dedupeKey());
            ps.setString(2, delivery.playerUuid().toString());
            ps.setLong(3, delivery.listingId());
            ps.setString(4, delivery.operationId());
            ps.setString(5, delivery.deliveryType().name());
            ps.setString(6, delivery.state().name());
            ps.setBytes(7, delivery.item().serializedData());
            ps.setString(8, delivery.item().codecVersion());
            ps.setString(9, delivery.item().hash());
            ps.setString(10, delivery.item().registryId());
            ps.setString(11, delivery.item().displayName());
            ps.setString(12, delivery.item().searchName());
            ps.setInt(13, delivery.item().quantity());
            ps.setLong(14, delivery.createdAt());
            setLongNullable(ps, 15, delivery.claimableAt());
            setLongNullable(ps, 16, delivery.claimStartedAt());
            setLongNullable(ps, 17, delivery.claimedAt());
            ps.setString(18, delivery.claimToken());
            ps.setString(19, delivery.lastError());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DatabaseException("delivery id not returned");
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new DatabaseException("delivery already exists (dedupe_key): " + delivery.dedupeKey(), e);
            }
            throw new DatabaseException("insert delivery failed", e);
        }
    }

    public Optional<AuctionDelivery> findById(Connection c, long deliveryId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_deliveries WHERE delivery_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, deliveryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find delivery failed: " + deliveryId, e);
        }
    }

    public Optional<AuctionDelivery> findByDedupeKey(Connection c, String dedupeKey) {
        String sql = "SELECT " + COLUMNS + " FROM auction_deliveries WHERE dedupe_key = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, dedupeKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find delivery by dedupe_key failed", e);
        }
    }

    /** Письма игрока, ожидающие выдачи (PENDING). */
    public List<AuctionDelivery> pendingForPlayer(Connection c, UUID playerUuid) {
        String sql = "SELECT " + COLUMNS + " FROM auction_deliveries WHERE player_uuid = ? AND state = 'PENDING' "
                + "ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<AuctionDelivery> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("pending deliveries of player failed: " + playerUuid, e);
        }
    }

    /** Письма в заданном состоянии (для recovery: CLAIMING и т.п.). */
    public List<AuctionDelivery> listByState(Connection c, DeliveryState state) {
        String sql = "SELECT " + COLUMNS + " FROM auction_deliveries WHERE state = ? ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<AuctionDelivery> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("deliveries by state failed: " + state, e);
        }
    }

    public List<AuctionDelivery> listByState(Connection c, DeliveryState state, int limit) {
        return listByStateAfter(c, state, 0L, limit);
    }

    /** Indexed keyset page for bounded/startup recovery. */
    public List<AuctionDelivery> listByStateAfter(Connection c, DeliveryState state,
                                                   long deliveryIdAfter, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM auction_deliveries "
                + "WHERE state = ? AND delivery_id > ? ORDER BY delivery_id LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setLong(2, deliveryIdAfter);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<AuctionDelivery> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("deliveries state page failed: " + state, e);
        }
    }

    /** Оптимистичное обновление изменяемого состояния delivery. */
    public boolean applyState(Connection c, AuctionDelivery expected, AuctionDelivery updated) {
        String sql = "UPDATE auction_deliveries SET state=?, claimable_at=?, claim_started_at=?, "
                + "claimed_at=?, claim_token=?, last_error=?, version = version + 1 "
                + "WHERE delivery_id = ? AND version = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, updated.state().name());
            setLongNullable(ps, 2, updated.claimableAt());
            setLongNullable(ps, 3, updated.claimStartedAt());
            setLongNullable(ps, 4, updated.claimedAt());
            ps.setString(5, updated.claimToken());
            ps.setString(6, updated.lastError());
            ps.setLong(7, expected.deliveryId());
            ps.setInt(8, expected.version());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("update delivery failed: " + expected.deliveryId(), e);
        }
    }

    /* ------------------------------- mapping ------------------------------- */

    private static AuctionDelivery map(ResultSet rs) throws SQLException {
        ItemSnapshot item = new ItemSnapshot(
                rs.getBytes("item_blob"),
                rs.getString("item_codec_version"),
                rs.getString("item_hash"),
                rs.getString("item_registry_id"),
                rs.getString("item_display_name"),
                rs.getString("item_search_name"),
                rs.getInt("quantity"));
        return new AuctionDelivery(
                rs.getLong("delivery_id"),
                rs.getString("dedupe_key"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("listing_id"),
                rs.getString("operation_id"),
                DeliveryType.valueOf(rs.getString("delivery_type")),
                DeliveryState.valueOf(rs.getString("state")),
                item,
                rs.getLong("created_at"),
                nullableLong(rs, "claimable_at"),
                nullableLong(rs, "claim_started_at"),
                nullableLong(rs, "claimed_at"),
                rs.getString("claim_token"),
                rs.getString("last_error"),
                rs.getInt("version"));
    }

    private static void setLongNullable(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && (state.startsWith("23") || state.startsWith("19"))) {
            return true;
        }
        // SQLite через наш драйвер может отдать нестандартный SQLState:
        // дублируем "UNIQUE constraint failed" / "PRIMARY KEY" из текста.
        String msg = e.getMessage();
        return msg != null && (msg.contains("UNIQUE constraint failed")
                || msg.contains("PRIMARY KEY") || msg.contains("constraint failed"));
    }
}

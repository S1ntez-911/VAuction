package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.listing.AuctionListing;
import com.valorcraft.vauction.domain.listing.ListingStatus;
import com.valorcraft.vauction.item.ItemSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий лотов. Только prepared statements; UUID — строки; никаких ников.
 * Изменяемые операции — строго через optimistic lock ({@code version}).
 */
public final class ListingRepository {

    public record ListingPage(List<AuctionListing> items, long total) {}

    private static final String COLUMNS = "listing_id, seller_uuid, status, "
            + "item_blob, item_codec_version, item_hash, item_registry_id, item_display_name, "
            + "item_search_name, quantity, price_minor, listing_fee_minor, commission_bps, "
            + "created_at, expires_at, updated_at, buyer_uuid, reservation_id, reserved_at, "
            + "reserved_until, cancel_reason, admin_actor_uuid, version";

    /** Вставить новый лот; возвращает сгенерированный listingId. */
    public long insert(Connection c, AuctionListing listing) {
        String sql = "INSERT INTO auction_listings (seller_uuid, status, item_blob, item_codec_version, "
                + "item_hash, item_registry_id, item_display_name, item_search_name, quantity, price_minor, "
                + "listing_fee_minor, commission_bps, created_at, expires_at, updated_at, buyer_uuid, "
                + "reservation_id, reserved_at, reserved_until, cancel_reason, admin_actor_uuid, version) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindListing(ps, listing);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DatabaseException("listing id not returned");
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new DatabaseException("insert listing failed", e);
        }
    }

    /** Mark a listing as belonging to the simple fixed-price player market. */
    public void markSimple(Connection c, long listingId, String category, long createdAt) {
        String sql = "INSERT INTO auction_simple_listing_ids (listing_id, category, created_at, state) VALUES (?,?,?,'PENDING')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            ps.setString(2, category);
            ps.setLong(3, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("mark simple listing failed: " + listingId, e);
        }
    }

    public boolean setSimpleState(Connection c, long listingId, String expected, String state) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE auction_simple_listing_ids SET state=? WHERE listing_id=? AND state=?")) {
            ps.setString(1, state);
            ps.setLong(2, listingId);
            ps.setString(3, expected);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("update simple listing state failed: " + listingId, e);
        }
    }

    /** Indexed, bounded page for the new catalogue. Old order-book data is deliberately excluded. */
    public ListingPage simpleActivePage(Connection c, UUID seller, String category,
                                        String search, int offset, int limit) {
        StringBuilder where = new StringBuilder(" FROM auction_listings l JOIN auction_simple_listing_ids s "
                + "ON s.listing_id=l.listing_id WHERE l.status='ACTIVE' AND s.state='ACTIVE'");
        List<Object> args = new ArrayList<>();
        if (seller != null) {
            where.append(" AND l.seller_uuid=?");
            args.add(seller.toString());
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND s.category=?");
            args.add(category);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND l.item_search_name LIKE ? ESCAPE '\\\\'");
            args.add("%" + escapeLike(search.toLowerCase(java.util.Locale.ROOT)) + "%");
        }
        try {
            long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*)" + where)) {
                bindArgs(ps, args);
                try (ResultSet rs = ps.executeQuery()) { total = rs.next() ? rs.getLong(1) : 0L; }
            }
            String sql = "SELECT " + prefixColumns("l.") + where
                    + " ORDER BY l.created_at DESC,l.listing_id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bindArgs(ps, args);
                ps.setInt(args.size() + 1, Math.max(1, limit));
                ps.setInt(args.size() + 2, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    List<AuctionListing> items = new ArrayList<>();
                    while (rs.next()) items.add(map(rs));
                    return new ListingPage(List.copyOf(items), total);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("simple listing page failed", e);
        }
    }

    public long countSimpleActive(Connection c, UUID seller) {
        String sql = "SELECT COUNT(*) FROM auction_listings l JOIN auction_simple_listing_ids s "
                + "ON s.listing_id=l.listing_id WHERE l.status='ACTIVE' AND s.state='ACTIVE' AND l.seller_uuid=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, seller.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        } catch (SQLException e) {
            throw new DatabaseException("count simple listings failed", e);
        }
    }

    public List<AuctionListing> simpleReserved(Connection c, int limit) {
        String sql = "SELECT " + prefixColumns("l.") + " FROM auction_listings l "
                + "JOIN auction_simple_listing_ids s ON s.listing_id=l.listing_id "
                + "WHERE l.status='RESERVED' ORDER BY l.updated_at,l.listing_id LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<AuctionListing> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("reserved simple listings failed", e);
        }
    }

    public List<AuctionListing> simpleExpiredActive(Connection c, long now, int limit) {
        String sql = "SELECT " + prefixColumns("l.") + " FROM auction_listings l "
                + "JOIN auction_simple_listing_ids s ON s.listing_id=l.listing_id "
                + "WHERE l.status='ACTIVE' AND s.state='ACTIVE' AND l.expires_at<=? "
                + "ORDER BY l.expires_at,l.listing_id LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<AuctionListing> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("expired simple listings failed", e);
        }
    }

    public Optional<AuctionListing> findById(Connection c, long listingId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_listings WHERE listing_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find listing failed: " + listingId, e);
        }
    }

    /** Активные лоты продавца (для лимита активных лотов на игрока). */
    public List<AuctionListing> activeFor(Connection c, UUID sellerUuid) {
        String sql = "SELECT " + COLUMNS + " FROM auction_listings WHERE seller_uuid = ? AND status IN ('ACTIVE','RESERVED') "
                + "ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sellerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<AuctionListing> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("active listings of seller failed: " + sellerUuid, e);
        }
    }

    /** Активные лоты по registryId предмета (для мгнов. матчинга заявок на покупку). */
    public List<AuctionListing> activeByRegistryId(Connection c, String registryId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_listings WHERE item_registry_id = ? AND status = 'ACTIVE' "
                + "ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, registryId);
            try (ResultSet rs = ps.executeQuery()) {
                List<AuctionListing> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("active listings by registry id failed: " + registryId, e);
        }
    }

    /**
     * Оптимистичное обновление всего изменяемого состояния лота.
     * Успех только если текущая версия в БД равна {@code expected.version()} —
     * именно так закрывается будущая гонка двух покупателей.
     */
    public boolean applyState(Connection c, AuctionListing expected, AuctionListing updated) {
        if (expected.listingId() != updated.listingId()) {
            throw new IllegalArgumentException("expected/updated listingId mismatch");
        }
        String sql = "UPDATE auction_listings SET status=?, buyer_uuid=?, reservation_id=?, "
                + "reserved_at=?, reserved_until=?, cancel_reason=?, admin_actor_uuid=?, updated_at=?, "
                + "version = version + 1, expires_at = ?, item_display_name = ?, item_search_name = ? "
                + "WHERE listing_id = ? AND version = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, updated.status().name());
            ps.setString(2, nullIfNull(updated.buyerUuid()));
            ps.setString(3, updated.reservationId());
            setLongNullable(ps, 4, updated.reservedAt());
            setLongNullable(ps, 5, updated.reservedUntil());
            ps.setString(6, updated.cancelReason());
            ps.setString(7, nullIfNull(updated.adminActorUuid()));
            ps.setLong(8, updated.updatedAt());
            ps.setLong(9, updated.expiresAt());
            ps.setString(10, updated.item().displayName());
            ps.setString(11, updated.item().searchName());
            ps.setLong(12, expected.listingId());
            ps.setInt(13, expected.version());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("update listing failed: " + expected.listingId(), e);
        }
    }

    /* ------------------------------- mapping ------------------------------- */

    private static void bindListing(PreparedStatement ps, AuctionListing l) throws SQLException {
        ps.setString(1, l.sellerUuid().toString());
        ps.setString(2, l.status().name());
        ps.setBytes(3, l.item().serializedData());
        ps.setString(4, l.item().codecVersion());
        ps.setString(5, l.item().hash());
        ps.setString(6, l.item().registryId());
        ps.setString(7, l.item().displayName());
        ps.setString(8, l.item().searchName());
        ps.setInt(9, l.item().quantity());
        ps.setLong(10, l.priceMinor());
        ps.setLong(11, l.listingFeeMinor());
        ps.setInt(12, l.commissionBps());
        ps.setLong(13, l.createdAt());
        ps.setLong(14, l.expiresAt());
        ps.setLong(15, l.updatedAt());
        ps.setString(16, nullIfNull(l.buyerUuid()));
        ps.setString(17, l.reservationId());
        setLongNullable(ps, 18, l.reservedAt());
        setLongNullable(ps, 19, l.reservedUntil());
        ps.setString(20, l.cancelReason());
        ps.setString(21, nullIfNull(l.adminActorUuid()));
    }

    private static AuctionListing map(ResultSet rs) throws SQLException {
        ItemSnapshot item = new ItemSnapshot(
                rs.getBytes("item_blob"),
                rs.getString("item_codec_version"),
                rs.getString("item_hash"),
                rs.getString("item_registry_id"),
                rs.getString("item_display_name"),
                rs.getString("item_search_name"),
                rs.getInt("quantity"));
        return new AuctionListing(
                rs.getLong("listing_id"),
                UUID.fromString(rs.getString("seller_uuid")),
                ListingStatus.valueOf(rs.getString("status")),
                item,
                rs.getLong("price_minor"),
                rs.getLong("listing_fee_minor"),
                rs.getInt("commission_bps"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                rs.getLong("updated_at"),
                uuidOrNull(rs, "buyer_uuid"),
                rs.getString("reservation_id"),
                nullableLong(rs, "reserved_at"),
                nullableLong(rs, "reserved_until"),
                rs.getString("cancel_reason"),
                uuidOrNull(rs, "admin_actor_uuid"),
                rs.getInt("version"));
    }

    private static void setLongNullable(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static UUID uuidOrNull(ResultSet rs, String column) throws SQLException {
        String s = rs.getString(column);
        return s == null ? null : UUID.fromString(s);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static String nullIfNull(UUID u) {
        return u == null ? null : u.toString();
    }

    private static String prefixColumns(String prefix) {
        return java.util.Arrays.stream(COLUMNS.split(", "))
                .map(column -> prefix + column).collect(java.util.stream.Collectors.joining(", "));
    }

    private static void bindArgs(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

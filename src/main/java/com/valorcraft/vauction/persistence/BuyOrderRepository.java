package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.buyorder.BuyOrder;
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
 * Репозиторий заявок на покупку (auction_buy_orders).
 * Переходы — через optimistic lock ({@code version}); активные заявки выбираются
 * по buyer или по registryId (дальнейшая фильтрация по тегам — в сервисе).
 */
public final class BuyOrderRepository {

    private static final String COLUMNS = "buy_order_id, buyer_uuid, "
            + "item_blob, item_codec_version, item_hash, item_registry_id, "
            + "item_display_name, item_search_name, quantity, price_per_unit, "
            + "total_requested, fulfilled_amount, active, ref_epoch, created_at, updated_at, version";

    public void insert(Connection c, BuyOrder order) {
        String sql = "INSERT INTO auction_buy_orders (buy_order_id, buyer_uuid, "
                + "item_blob, item_codec_version, item_hash, item_registry_id, "
                + "item_display_name, item_search_name, quantity, price_per_unit, "
                + "total_requested, fulfilled_amount, active, ref_epoch, created_at, updated_at, version) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, order.buyOrderId().toString());
            ps.setString(2, order.buyerUuid().toString());
            ps.setBytes(3, order.item().serializedData());
            ps.setString(4, order.item().codecVersion());
            ps.setString(5, order.item().hash());
            ps.setString(6, order.item().registryId());
            ps.setString(7, order.item().displayName());
            ps.setString(8, order.item().searchName());
            ps.setInt(9, order.item().quantity());
            ps.setLong(10, order.pricePerUnit());
            ps.setInt(11, order.totalRequested());
            ps.setInt(12, order.fulfilledAmount());
            ps.setInt(13, order.active() ? 1 : 0);
            ps.setInt(14, order.refEpoch());
            ps.setLong(15, order.createdAt());
            ps.setLong(16, order.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("insert buy order failed: " + order.buyOrderId(), e);
        }
    }

    public Optional<BuyOrder> findById(Connection c, UUID buyOrderId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_buy_orders WHERE buy_order_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, buyOrderId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find buy order failed: " + buyOrderId, e);
        }
    }

    public List<BuyOrder> activeForBuyer(Connection c, UUID buyerUuid) {
        String sql = "SELECT " + COLUMNS + " FROM auction_buy_orders WHERE buyer_uuid = ? AND active = 1 "
                + "ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, buyerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("active buy orders for buyer failed", e);
        }
    }

    /** Активные заявки на предмет с указанным registry id (фильтрация по тегам — в сервисе). */
    public List<BuyOrder> activeByRegistryId(Connection c, String registryId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_buy_orders WHERE item_registry_id = ? AND active = 1 "
                + "ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, registryId);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("active buy orders by registry id failed", e);
        }
    }

    public List<BuyOrder> allActive(Connection c) {
        String sql = "SELECT " + COLUMNS + " FROM auction_buy_orders WHERE active = 1 ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return mapAll(rs);
        } catch (SQLException e) {
            throw new DatabaseException("all active buy orders failed", e);
        }
    }

    /** Оптимистичное обновление (CAS по version). */
    public boolean applyState(Connection c, BuyOrder expected, BuyOrder updated) {
        if (!expected.buyOrderId().equals(updated.buyOrderId())) {
            throw new IllegalArgumentException("expected/updated buyOrderId mismatch");
        }
        String sql = "UPDATE auction_buy_orders SET fulfilled_amount=?, active=?, ref_epoch=?, "
                + "updated_at=?, version = version + 1 WHERE buy_order_id = ? AND version = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, updated.fulfilledAmount());
            ps.setInt(2, updated.active() ? 1 : 0);
            ps.setInt(3, updated.refEpoch());
            ps.setLong(4, updated.updatedAt());
            ps.setString(5, expected.buyOrderId().toString());
            ps.setInt(6, expected.version());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("update buy order failed: " + expected.buyOrderId(), e);
        }
    }

    public void delete(Connection c, UUID buyOrderId) {
        String sql = "DELETE FROM auction_buy_orders WHERE buy_order_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, buyOrderId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("delete buy order failed: " + buyOrderId, e);
        }
    }

    /* ------------------------------- mapping ------------------------------- */

    private static List<BuyOrder> mapAll(ResultSet rs) throws SQLException {
        List<BuyOrder> orders = new ArrayList<>();
        while (rs.next()) {
            orders.add(map(rs));
        }
        return orders;
    }

    private static BuyOrder map(ResultSet rs) throws SQLException {
        ItemSnapshot item = new ItemSnapshot(
                rs.getBytes("item_blob"),
                rs.getString("item_codec_version"),
                rs.getString("item_hash"),
                rs.getString("item_registry_id"),
                rs.getString("item_display_name"),
                rs.getString("item_search_name"),
                rs.getInt("quantity"));
        return new BuyOrder(
                UUID.fromString(rs.getString("buy_order_id")),
                UUID.fromString(rs.getString("buyer_uuid")),
                item,
                rs.getLong("price_per_unit"),
                rs.getInt("total_requested"),
                rs.getInt("fulfilled_amount"),
                rs.getInt("active") == 1,
                rs.getInt("ref_epoch"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("version"));
    }
}
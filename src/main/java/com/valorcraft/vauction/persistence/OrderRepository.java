package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.market.OrderBookLevel;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderStatus;
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
 * Репозиторий единого стакана (auction_orders).
 * Матчинг выражен SQL ORDER BY + LIMIT (price-time priority) — без чтения
 * всего стакана в Java. Все мутации — через условные UPDATE (CAS по version
 * и по факту оставшегося количества: двойной fill физически невозможен).
 */
public final class OrderRepository {

    private static final String COLUMNS = "order_id, owner_uuid, side, status, market_key, "
            + "item_blob, item_codec_version, item_hash, item_registry_id, item_display_name, "
            + "item_search_name, item_snapshot_qty, price_per_unit, original_quantity, remaining_quantity, "
            + "filled_quantity, escrow_reference, ref_epoch, created_at, updated_at, version";

    public void insert(Connection c, Order order) {
        String sql = "INSERT INTO auction_orders (order_id, owner_uuid, side, status, market_key, "
                + "item_blob, item_codec_version, item_hash, item_registry_id, item_display_name, "
                + "item_search_name, item_snapshot_qty, price_per_unit, original_quantity, remaining_quantity, "
                + "filled_quantity, escrow_reference, ref_epoch, created_at, updated_at, version) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindOrder(ps, order);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("insert auction_order failed: " + order.orderId(), e);
        }
    }

    public Optional<Order> findById(Connection c, UUID orderId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_orders WHERE order_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, orderId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find auction_order failed: " + orderId, e);
        }
    }

    /** Активные ордера игрока (для лимитов). */
    public List<Order> activeForOwner(Connection c, UUID ownerUuid) {
        String sql = "SELECT " + COLUMNS + " FROM auction_orders "
                + "WHERE owner_uuid = ? AND status = 'ACTIVE' ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("active orders of player failed", e);
        }
    }

    /**
     * Resting SELL-ордера для нового BUY: наименьшая цена, при равенстве —
     * старейший первый (price-time priority). LIMIT — не более N лучших.
     */
    public List<Order> bestSells(Connection c, String marketKey, long priceLimit, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM auction_orders "
                + "WHERE market_key = ? AND side = 'SELL' AND status = 'ACTIVE' AND price_per_unit <= ? "
                + "ORDER BY price_per_unit ASC, created_at ASC LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, marketKey);
            ps.setLong(2, priceLimit);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("resting sells failed", e);
        }
    }

    /**
     * Rest- BUY-ордера для нового SELL: наивысшая цена, затем старейший первый.
     */
    public List<Order> bestBuys(Connection c, String marketKey, long priceFloor, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM auction_orders "
                + "WHERE market_key = ? AND side = 'BUY' AND status = 'ACTIVE' AND price_per_unit >= ? "
                + "ORDER BY price_per_unit DESC, created_at ASC LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, marketKey);
            ps.setLong(2, priceFloor);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("resting buys failed", e);
        }
    }

    // ------------------------------------------------------------ мутации (CAS)

    /**
     * Оптимистичное обновление произвольного состояния (cancel/refreeze/…).
     * Успех — только если version в БД совпадает с {@code expected.version()}.
     */
    public boolean applyState(Connection c, Order expected, Order updated) {
        if (!expected.orderId().equals(updated.orderId())) {
            throw new IllegalArgumentException("orderId mismatch");
        }
        String sql = "UPDATE auction_orders SET status = ?, remaining_quantity = ?, "
                + "filled_quantity = ?, escrow_reference = ?, ref_epoch = ?, updated_at = ?, "
                + "version = version + 1 WHERE order_id = ? AND version = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, updated.status().name());
            ps.setInt(2, updated.remainingQuantity());
            ps.setInt(3, updated.filledQuantity());
            ps.setString(4, updated.escrowReference());
            ps.setInt(5, updated.refEpoch());
            ps.setLong(6, updated.updatedAt());
            ps.setString(7, expected.orderId().toString());
            ps.setInt(8, expected.version());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("update auction_order failed: " + expected.orderId(), e);
        }
    }

    /**
     * ДЕДИКАЦИОННАЯ безопасная дедуция остатка: списывает {@code doneNeed} только
     * если строковая версия ожидаемa и остатка хватает. Возвращает новый остаток
     * либо {@code null}, если запись изменилась (стоит повторить чтение).
     */
    public Integer tryConsume(Connection c, Order expected, int done, long now) {
        if (done <= 0) {
            throw new IllegalArgumentException("done must be > 0");
        }
        String sql = "UPDATE auction_orders "
                + "SET remaining_quantity = remaining_quantity - ?, "
                + "    filled_quantity = filled_quantity + ?, "
                + "    status = CASE WHEN remaining_quantity - ? <= 0 THEN 'FILLED' ELSE 'ACTIVE' END, "
                + "    updated_at = ?, version = version + 1 "
                + "WHERE order_id = ? AND status = 'ACTIVE'"
                + "  AND version = ? AND remaining_quantity >= ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, done);
            ps.setInt(2, done);
            ps.setInt(3, done);
            ps.setLong(4, now);
            ps.setString(5, expected.orderId().toString());
            ps.setInt(6, expected.version());
            ps.setInt(7, done);
            return ps.executeUpdate() == 1
                    ? expected.remainingQuantity() - done
                    : null;
        } catch (SQLException e) {
            throw new DatabaseException("consume auction_order failed: " + expected.orderId(), e);
        }
    }

    // ------------------------------------------------------------ order book

    /** Уровни стакана по стороне (GROUP BY цена → суммарный остаток). */
    public List<OrderBookLevel> bookLevels(Connection c, String marketKey, OrderSide side) {
        String sql = "SELECT price_per_unit, SUM(remaining_quantity) AS qty FROM auction_orders "
                + "WHERE market_key = ? AND side = ? AND status = 'ACTIVE' "
                + "GROUP BY price_per_unit ORDER BY price_per_unit "
                + (side == OrderSide.BUY ? "DESC" : "ASC");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, marketKey);
            ps.setString(2, side.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<OrderBookLevel> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new OrderBookLevel(rs.getLong(1), rs.getLong(2)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("order book levels failed", e);
        }
    }

    /** Лучшая (минимальная) встречная SELL-цена, или 0, если нет. */
    public long bestPrice(Connection c, String marketKey, OrderSide side) {
        String sql = "SELECT price_per_unit FROM auction_orders "
                + "WHERE market_key = ? AND side = ? AND status = 'ACTIVE' "
                + "ORDER BY price_per_unit " + (side == OrderSide.SELL ? "ASC" : "DESC") + " LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, marketKey);
            ps.setString(2, side.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new DatabaseException("best price failed", e);
        }
    }

    /** Суммарный остаток по стороне (единицы), или 0. */
    public long totalRemaining(Connection c, String marketKey, OrderSide side) {
        String sql = "SELECT COALESCE(SUM(remaining_quantity), 0) FROM auction_orders "
                + "WHERE market_key = ? AND side = ? AND status = 'ACTIVE'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, marketKey);
            ps.setString(2, side.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new DatabaseException("total remaining failed", e);
        }
    }

    /** Старейшие активные ордера стороны с created_at <= среза (для expiry). */
    public List<Order> oldestActive(Connection c, OrderSide side, long createdBefore, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM auction_orders "
                + "WHERE side = ? AND status = 'ACTIVE' AND created_at <= ? "
                + "ORDER BY created_at ASC LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, side.name());
            ps.setLong(2, createdBefore);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("oldest active failed", e);
        }
    }

    /* ------------------------------- mapping ------------------------------- */

    private static List<Order> mapAll(ResultSet rs) throws SQLException {
        List<Order> out = new ArrayList<>();
        while (rs.next()) {
            out.add(map(rs));
        }
        return out;
    }

    private static void bindOrder(PreparedStatement ps, Order o) throws SQLException {
        ps.setString(1, o.orderId().toString());
        ps.setString(2, o.ownerUuid().toString());
        ps.setString(3, o.side().name());
        ps.setString(4, o.status().name());
        ps.setString(5, o.marketKey());
        ps.setBytes(6, o.item().serializedData());
        ps.setString(7, o.item().codecVersion());
        ps.setString(8, o.item().hash());
        ps.setString(9, o.item().registryId());
        ps.setString(10, o.item().displayName());
        ps.setString(11, o.item().searchName());
        ps.setLong(12, o.item().quantity());
        ps.setInt(13, o.originalQuantity());
        ps.setInt(14, o.remainingQuantity());
        ps.setInt(15, o.filledQuantity());
        ps.setString(16, o.escrowReference());
        ps.setInt(17, o.refEpoch());
        ps.setLong(18, o.createdAt());
        ps.setLong(19, o.updatedAt());
    }

    private static Order map(ResultSet rs) throws SQLException {
        ItemSnapshot item = new ItemSnapshot(
                rs.getBytes("item_blob"),
                rs.getString("item_codec_version"),
                rs.getString("item_hash"),
                rs.getString("item_registry_id"),
                rs.getString("item_display_name"),
                rs.getString("item_search_name"),
                rs.getInt("item_snapshot_qty"));
        String escrow = rs.getString("escrow_reference");
        return new Order(
                UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("owner_uuid")),
                OrderSide.valueOf(rs.getString("side")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("market_key"),
                item,
                rs.getLong("price_per_unit"),
                rs.getInt("original_quantity"),
                rs.getInt("remaining_quantity"),
                rs.getInt("filled_quantity"),
                escrow,
                rs.getInt("ref_epoch"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("version"));
    }
}
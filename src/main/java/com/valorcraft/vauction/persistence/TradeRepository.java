package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.trade.Trade;
import com.valorcraft.vauction.domain.trade.TradeState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий fill'ов (auction_trades). trade_id — стабильный UUID: retry с тем же
 * id не создаёт второй Trade (уникальный ключ + CAS). Каждый fill — отдельный Trade.
 */
public final class TradeRepository {

    public record Cursor(long settledAt, String tradeId) {
        public static Cursor empty() { return new Cursor(0, ""); }
    }

    public record PlayerSummary(long fills, long boughtQuantity, long soldQuantity,
                                long completedOrders, long partialOrders, Cursor cursor) {
        public boolean empty() { return fills == 0; }
    }

    private static final String COLUMNS = "trade_id, market_key, buy_order_id, sell_order_id, "
            + "maker_side, execution_price, quantity, gross_minor, commission_minor, "
            + "seller_net_minor, buyer_uuid, seller_uuid, escrow_reference, state, "
            + "created_at, settled_at, version";

    public void insert(Connection c, Trade trade) {
        String sql = "INSERT INTO auction_trades (trade_id, market_key, buy_order_id, sell_order_id, "
                + "maker_side, execution_price, quantity, gross_minor, commission_minor, "
                + "seller_net_minor, buyer_uuid, seller_uuid, escrow_reference, state, "
                + "created_at, settled_at, version) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, trade.tradeId().toString());
            ps.setString(2, trade.marketKey());
            ps.setString(3, trade.buyOrderId().toString());
            ps.setString(4, trade.sellOrderId().toString());
            ps.setString(5, trade.makerSide().name());
            ps.setLong(6, trade.executionPrice());
            ps.setInt(7, trade.quantity());
            ps.setLong(8, trade.grossMinor());
            ps.setLong(9, trade.commissionMinor());
            ps.setLong(10, trade.sellerNetMinor());
            ps.setString(11, trade.buyerUuid().toString());
            ps.setString(12, trade.sellerUuid().toString());
            ps.setString(13, trade.escrowReference());
            ps.setString(14, trade.state().name());
            ps.setLong(15, trade.createdAt());
            setLongNullable(ps, 16, trade.settledAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("insert trade failed: " + trade.tradeId(), e);
        }
    }

    public Optional<Trade> findById(Connection c, UUID tradeId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_trades WHERE trade_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tradeId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find trade failed: " + tradeId, e);
        }
    }

    /** Fill'и по ордеру (для recovery/аудита). */
    public List<Trade> byOrderId(Connection c, UUID orderId, boolean asBuy, boolean asSell) {
        String sql = "SELECT " + COLUMNS + " FROM auction_trades WHERE "
                + (asBuy ? "buy_order_id = ?" : "") + (asBuy && asSell ? " OR " : "")
                + (asSell ? "sell_order_id = ?" : "") + " ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (asBuy && asSell) {
                ps.setString(1, orderId.toString());
                ps.setString(2, orderId.toString());
            } else {
                ps.setString(1, orderId.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Trade> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("trades by order failed", e);
        }
    }

    /** Последняя цена сделки по рынку (или 0). */
    public long lastTradePrice(Connection c, String marketKey) {
        String sql = "SELECT execution_price FROM auction_trades WHERE market_key = ? AND state = 'SETTLED' "
                + "ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, marketKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new DatabaseException("last trade price failed", e);
        }
    }

    /**
     * Отметить fill исполненным (CAS по PENDING): вернёт true, если переход
     * прошёл; false — запись уже не PENDING (повтор).
     */
    public boolean markSettled(Connection c, Trade expected, long now) {
        String sql = "UPDATE auction_trades SET state = 'SETTLED', settled_at = ?, version = version + 1 "
                + "WHERE trade_id = ? AND state = 'PENDING' AND version = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, expected.tradeId().toString());
            ps.setInt(3, expected.version());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("settle trade failed: " + expected.tradeId(), e);
        }
    }

    /** Отметить fill проваленным (CAS по PENDING). */
    public boolean markFailed(Connection c, Trade expected) {
        String sql = "UPDATE auction_trades SET state = 'FAILED', version = version + 1 "
                + "WHERE trade_id = ? AND state = 'PENDING' AND version = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, expected.tradeId().toString());
            ps.setInt(2, expected.version());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("fail trade failed: " + expected.tradeId(), e);
        }
    }

    /** Все fill'ы (для recovery/аудита). */
    public List<Trade> findAll(Connection c) {
        String sql = "SELECT " + COLUMNS + " FROM auction_trades ORDER BY created_at";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("all trades failed", e);
        }
    }

    public Cursor latestForPlayer(Connection c, UUID playerId) {
        String sql = "SELECT settled_at,trade_id FROM ("
                + "SELECT settled_at,trade_id FROM auction_trades WHERE buyer_uuid=? AND state='SETTLED' "
                + "UNION ALL SELECT settled_at,trade_id FROM auction_trades WHERE seller_uuid=? AND state='SETTLED') "
                + "ORDER BY settled_at DESC,trade_id DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Cursor(rs.getLong(1), rs.getString(2)) : Cursor.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("latest player trade cursor failed", e);
        }
    }

    public PlayerSummary summaryAfter(Connection c, UUID playerId, long afterAt, String afterId) {
        String sql = "SELECT COUNT(*),"
                + "COALESCE(SUM(CASE WHEN t.buyer_uuid=? THEN t.quantity ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN t.seller_uuid=? THEN t.quantity ELSE 0 END),0),"
                + "COUNT(DISTINCT CASE WHEN t.buyer_uuid=? AND bo.status='FILLED' THEN t.buy_order_id "
                + "WHEN t.seller_uuid=? AND so.status='FILLED' THEN t.sell_order_id END),"
                + "COUNT(DISTINCT CASE WHEN t.buyer_uuid=? AND bo.status='ACTIVE' THEN t.buy_order_id "
                + "WHEN t.seller_uuid=? AND so.status='ACTIVE' THEN t.sell_order_id END) "
                + "FROM auction_trades t "
                + "LEFT JOIN auction_orders bo ON bo.order_id=t.buy_order_id "
                + "LEFT JOIN auction_orders so ON so.order_id=t.sell_order_id "
                + "WHERE t.state='SETTLED' AND (t.buyer_uuid=? OR t.seller_uuid=?) "
                + "AND (t.settled_at>? OR (t.settled_at=? AND t.trade_id>?))";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= 6; i++) ps.setString(i, playerId.toString());
            ps.setString(7, playerId.toString());
            ps.setString(8, playerId.toString());
            ps.setLong(9, afterAt);
            ps.setLong(10, afterAt);
            ps.setString(11, afterId == null ? "" : afterId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Cursor latest = latestForPlayer(c, playerId);
                return new PlayerSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), latest);
            }
        } catch (SQLException e) {
            throw new DatabaseException("player trade summary failed", e);
        }
    }

    /** Indexed bounded recovery query; never materializes settled history. */
    public List<Trade> findPending(Connection c, int limit) {
        return findPendingAfter(c, Long.MIN_VALUE, "", limit);
    }

    /** Keyset page used by startup recovery. */
    public List<Trade> findPendingAfter(Connection c, long createdAfter, String tradeIdAfter,
                                        int limit) {
        String sql = "SELECT " + COLUMNS + " FROM auction_trades "
                + "WHERE state='PENDING' AND (created_at > ? OR (created_at = ? AND trade_id > ?)) "
                + "ORDER BY created_at, trade_id LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, createdAfter);
            ps.setLong(2, createdAfter);
            ps.setString(3, tradeIdAfter == null ? "" : tradeIdAfter);
            ps.setInt(4, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("pending trades page failed", e);
        }
    }

    private static List<Trade> mapAll(ResultSet rs) throws SQLException {
        List<Trade> out = new ArrayList<>();
        while (rs.next()) {
            out.add(map(rs));
        }
        return out;
    }

    /* ------------------------------- mapping ------------------------------- */

    private static Trade map(ResultSet rs) throws SQLException {
        return new Trade(
                UUID.fromString(rs.getString("trade_id")),
                rs.getString("market_key"),
                UUID.fromString(rs.getString("buy_order_id")),
                UUID.fromString(rs.getString("sell_order_id")),
                com.valorcraft.vauction.domain.order.OrderSide.valueOf(rs.getString("maker_side")),
                rs.getLong("execution_price"),
                rs.getInt("quantity"),
                rs.getLong("gross_minor"),
                rs.getLong("commission_minor"),
                rs.getLong("seller_net_minor"),
                UUID.fromString(rs.getString("buyer_uuid")),
                UUID.fromString(rs.getString("seller_uuid")),
                rs.getString("escrow_reference"),
                TradeState.valueOf(rs.getString("state")),
                rs.getLong("created_at"),
                nullableLong(rs, "settled_at"),
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
}

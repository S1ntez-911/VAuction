package com.valorcraft.vauction.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable marker for immediate-or-cancel orders that must never rest indefinitely. */
public final class IocOrderRepository {
    public void mark(Connection c, UUID orderId, long createdAt) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO auction_ioc_orders(order_id, created_at) VALUES (?,?)")) {
            ps.setString(1, orderId.toString());
            ps.setLong(2, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("mark IOC order failed", e);
        }
    }

    public void remove(Connection c, UUID orderId) {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM auction_ioc_orders WHERE order_id=?")) {
            ps.setString(1, orderId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("remove IOC marker failed", e);
        }
    }

    public List<UUID> oldest(Connection c, int limit) {
        String sql = "SELECT order_id FROM auction_ioc_orders ORDER BY created_at, order_id LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<UUID> out = new ArrayList<>();
                while (rs.next()) out.add(UUID.fromString(rs.getString(1)));
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("IOC marker page failed", e);
        }
    }

    public boolean exists(Connection c, UUID orderId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM auction_ioc_orders WHERE order_id=? LIMIT 1")) {
            ps.setString(1, orderId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DatabaseException("IOC marker lookup failed", e);
        }
    }
}

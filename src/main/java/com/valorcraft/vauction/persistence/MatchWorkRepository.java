package com.valorcraft.vauction.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Persistent FIFO continuation queue for bounded order matching. */
public final class MatchWorkRepository {

    public record MatchWork(long workId, UUID orderId, long createdAt,
                            long nextAttemptAt, int attemptCount) {}

    public void registerOrder(Connection c, UUID orderId) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO auction_order_acceptance (order_id) VALUES (?)")) {
            ps.setString(1, orderId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("register order acceptance failed: " + orderId, e);
        }
    }

    public void enqueue(Connection c, UUID orderId, long createdAt) {
        String sql = "INSERT OR IGNORE INTO auction_match_queue "
                + "(order_id, created_at, next_attempt_at, attempt_count) VALUES (?, ?, ?, 0)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, orderId.toString());
            ps.setLong(2, createdAt);
            ps.setLong(3, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("enqueue matching failed: " + orderId, e);
        }
    }

    public Optional<MatchWork> pollReady(Connection c, long now) {
        String sql = "SELECT work_id, order_id, created_at, next_attempt_at, attempt_count "
                + "FROM auction_match_queue WHERE next_attempt_at <= ? "
                + "ORDER BY next_attempt_at, created_at, work_id LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("poll matching queue failed", e);
        }
    }

    public void defer(Connection c, long workId, long nextAttemptAt) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE auction_match_queue SET next_attempt_at=?, attempt_count=attempt_count+1 "
                        + "WHERE work_id=?")) {
            ps.setLong(1, nextAttemptAt);
            ps.setLong(2, workId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("defer matching work failed: " + workId, e);
        }
    }

    public void readyNow(Connection c, long workId, long now) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE auction_match_queue SET next_attempt_at=? WHERE work_id=?")) {
            ps.setLong(1, now);
            ps.setLong(2, workId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("reschedule matching work failed: " + workId, e);
        }
    }

    public void delete(Connection c, long workId) {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM auction_match_queue WHERE work_id=?")) {
            ps.setLong(1, workId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("delete matching work failed: " + workId, e);
        }
    }

    public Optional<MatchWork> findByOrderId(Connection c, UUID orderId) {
        String sql = "SELECT work_id, order_id, created_at, next_attempt_at, attempt_count "
                + "FROM auction_match_queue WHERE order_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, orderId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find matching work failed: " + orderId, e);
        }
    }

    public int count(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM auction_match_queue");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("count matching queue failed", e);
        }
    }

    public boolean hasAny(Connection c) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM auction_match_queue LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            throw new DatabaseException("check matching queue failed", e);
        }
    }

    private static MatchWork map(ResultSet rs) throws SQLException {
        return new MatchWork(rs.getLong("work_id"), UUID.fromString(rs.getString("order_id")),
                rs.getLong("created_at"), rs.getLong("next_attempt_at"),
                rs.getInt("attempt_count"));
    }
}

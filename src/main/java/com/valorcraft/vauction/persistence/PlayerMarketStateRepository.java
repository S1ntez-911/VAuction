package com.valorcraft.vauction.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Minimal durable cursor; trades/orders/deliveries remain the source of truth. */
public final class PlayerMarketStateRepository {
    public record State(UUID playerId, long tradeAt, String tradeId,
                        long deliveryId, boolean onboardingShown) {}

    public Optional<State> find(Connection c, UUID playerId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT last_seen_trade_at,last_seen_trade_id,last_seen_delivery_id,onboarding_shown "
                        + "FROM auction_player_market_state WHERE player_uuid=?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new State(playerId, rs.getLong(1), rs.getString(2),
                        rs.getLong(3), rs.getInt(4) != 0)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("player market state lookup failed", e);
        }
    }

    public void insertCurrent(Connection c, UUID playerId, long tradeAt, String tradeId,
                              long deliveryId) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO auction_player_market_state"
                        + "(player_uuid,last_seen_trade_at,last_seen_trade_id,last_seen_delivery_id,onboarding_shown) "
                        + "VALUES (?,?,?,?,0)")) {
            ps.setString(1, playerId.toString());
            ps.setLong(2, tradeAt);
            ps.setString(3, tradeId == null ? "" : tradeId);
            ps.setLong(4, deliveryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("player market state insert failed", e);
        }
    }

    public void advance(Connection c, UUID playerId, long tradeAt, String tradeId, long deliveryId) {
        insertCurrent(c, playerId, 0, "", 0);
        State old = find(c, playerId).orElseThrow();
        boolean newerTrade = tradeAt > old.tradeAt()
                || (tradeAt == old.tradeAt() && tradeId != null && tradeId.compareTo(old.tradeId()) > 0);
        long nextAt = newerTrade ? tradeAt : old.tradeAt();
        String nextId = newerTrade ? tradeId : old.tradeId();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE auction_player_market_state SET last_seen_trade_at=?,last_seen_trade_id=?,"
                        + "last_seen_delivery_id=? WHERE player_uuid=?")) {
            ps.setLong(1, nextAt);
            ps.setString(2, nextId == null ? "" : nextId);
            ps.setLong(3, Math.max(old.deliveryId(), deliveryId));
            ps.setString(4, playerId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("player market cursor advance failed", e);
        }
    }

    /** @return true only for the first market open. */
    public boolean markOnboardingShown(Connection c, UUID playerId) {
        insertCurrent(c, playerId, 0, "", 0);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE auction_player_market_state SET onboarding_shown=1 "
                        + "WHERE player_uuid=? AND onboarding_shown=0")) {
            ps.setString(1, playerId.toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("onboarding marker failed", e);
        }
    }
}

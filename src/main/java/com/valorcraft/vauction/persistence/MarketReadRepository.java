package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.market.MarketCard;
import com.valorcraft.vauction.domain.market.MarketSummary;
import com.valorcraft.vauction.item.ItemSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Single-statement, bounded read model for home, search and market refreshes. */
public final class MarketReadRepository {

    private static final String CARD_SELECT = "SELECT p.market_key, v.item_blob, v.item_codec_version, "
            + "v.item_hash, v.item_registry_id, v.item_display_name, v.item_search_name, "
            + "v.item_snapshot_qty, "
            + "COALESCE(MAX(CASE WHEN o.side='BUY' AND o.status='ACTIVE' AND o.processing_state='NONE' "
            + "THEN o.price_per_unit END),0) best_bid, "
            + "COALESCE(MIN(CASE WHEN o.side='SELL' AND o.status='ACTIVE' AND o.processing_state='NONE' "
            + "THEN o.price_per_unit END),0) best_ask, "
            + "COALESCE(SUM(CASE WHEN o.side='BUY' AND o.status='ACTIVE' AND o.processing_state='NONE' "
            + "THEN o.remaining_quantity ELSE 0 END),0) buy_qty, "
            + "COALESCE(SUM(CASE WHEN o.side='SELL' AND o.status='ACTIVE' AND o.processing_state='NONE' "
            + "THEN o.remaining_quantity ELSE 0 END),0) sell_qty, "
            + "COALESCE((SELECT t.execution_price FROM auction_trades t WHERE t.market_key=p.market_key "
            + "AND t.state='SETTLED' ORDER BY t.settled_at DESC, t.trade_id DESC LIMIT 1),0) last_price "
            + "FROM paged p JOIN auction_orders v ON v.order_id=(SELECT vo.order_id FROM auction_orders vo "
            + "WHERE vo.market_key=p.market_key ORDER BY CASE WHEN vo.status='ACTIVE' "
            + "AND vo.processing_state='NONE' THEN 0 ELSE 1 END, vo.updated_at DESC, vo.order_id LIMIT 1) "
            + "LEFT JOIN auction_orders o ON o.market_key=p.market_key GROUP BY p.market_key";

    public List<MarketCard> page(Connection c, String rawQuery, long recentCutoff,
                                 int offset, int limit) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        boolean searching = !query.isEmpty();
        String candidates = searching
                ? "WITH paged AS (SELECT market_key, MAX(updated_at) activity FROM auction_orders "
                + "WHERE status='ACTIVE' AND processing_state='NONE' AND item_search_name LIKE ? ESCAPE '\\' "
                + "GROUP BY market_key ORDER BY activity DESC, market_key LIMIT ? OFFSET ?) "
                : "WITH active AS (SELECT market_key, MAX(updated_at) activity FROM auction_orders "
                + "WHERE status='ACTIVE' AND processing_state='NONE' GROUP BY market_key), "
                + "recent AS (SELECT market_key, MAX(settled_at) activity FROM auction_trades "
                + "WHERE state='SETTLED' AND settled_at>=? GROUP BY market_key), "
                + "candidate AS (SELECT market_key, MAX(activity) activity FROM "
                + "(SELECT * FROM active UNION ALL SELECT * FROM recent) GROUP BY market_key), "
                + "paged AS (SELECT market_key, activity FROM candidate ORDER BY activity DESC, market_key "
                + "LIMIT ? OFFSET ?) ";
        try (PreparedStatement ps = c.prepareStatement(candidates + CARD_SELECT)) {
            int i = 1;
            if (searching) {
                ps.setString(i++, "%" + escapeLike(query) + "%");
            } else {
                ps.setLong(i++, recentCutoff);
            }
            ps.setInt(i++, Math.max(1, limit));
            ps.setInt(i, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<MarketCard> out = new ArrayList<>();
                while (rs.next()) out.add(mapCard(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new DatabaseException("market GUI page failed", e);
        }
    }

    public Optional<MarketCard> byKey(Connection c, String marketKey) {
        String sql = "WITH paged AS (SELECT ? market_key) " + CARD_SELECT;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, marketKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCard(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("market GUI summary failed", e);
        }
    }

    private static MarketCard mapCard(ResultSet rs) throws SQLException {
        ItemSnapshot visual = new ItemSnapshot(rs.getBytes("item_blob"),
                rs.getString("item_codec_version"), rs.getString("item_hash"),
                rs.getString("item_registry_id"), rs.getString("item_display_name"),
                rs.getString("item_search_name"), rs.getInt("item_snapshot_qty"));
        MarketSummary summary = new MarketSummary(rs.getString("market_key"), visual.displayName(),
                rs.getLong("best_bid"), rs.getLong("best_ask"), rs.getLong("buy_qty"),
                rs.getLong("sell_qty"), rs.getLong("last_price"));
        return new MarketCard(summary, visual);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

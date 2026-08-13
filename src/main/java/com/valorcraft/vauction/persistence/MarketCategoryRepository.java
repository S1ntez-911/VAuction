package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.item.ItemSnapshot;
import com.valorcraft.vauction.item.MarketCategory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class MarketCategoryRepository {

    public record StoredMarket(String marketKey, ItemSnapshot item) {}

    public void upsert(Connection connection, String marketKey, MarketCategory category, long now) {
        String sql = "INSERT INTO auction_market_categories (market_key, category, classified_at) VALUES (?,?,?) "
                + "ON CONFLICT(market_key) DO UPDATE SET category=excluded.category, "
                + "classified_at=excluded.classified_at";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, marketKey);
            ps.setString(2, category.id());
            ps.setLong(3, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("market category upsert failed: " + marketKey, e);
        }
    }

    public List<StoredMarket> marketsAfter(Connection connection, String afterMarketKey, int limit) {
        String sql = "SELECT o.market_key, o.item_blob, o.item_codec_version, o.item_hash, "
                + "o.item_registry_id, o.item_display_name, o.item_search_name, o.item_snapshot_qty "
                + "FROM auction_orders o WHERE o.market_key>? "
                + "AND o.order_id=(SELECT x.order_id FROM auction_orders x "
                + "WHERE x.market_key=o.market_key ORDER BY x.updated_at DESC, x.order_id LIMIT 1) "
                + "ORDER BY o.market_key LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, afterMarketKey == null ? "" : afterMarketKey);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<StoredMarket> result = new ArrayList<>();
                while (rs.next()) {
                    ItemSnapshot item = new ItemSnapshot(rs.getBytes("item_blob"),
                            rs.getString("item_codec_version"), rs.getString("item_hash"),
                            rs.getString("item_registry_id"), rs.getString("item_display_name"),
                            rs.getString("item_search_name"), rs.getInt("item_snapshot_qty"));
                    result.add(new StoredMarket(rs.getString("market_key"), item));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("stored markets query failed", e);
        }
    }
}

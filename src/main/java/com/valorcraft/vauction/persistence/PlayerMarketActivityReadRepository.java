package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.application.PlayerMarketActivity;
import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderStatus;
import com.valorcraft.vauction.item.ItemSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded deterministic read model for relevant orders and claimable deliveries. */
public final class PlayerMarketActivityReadRepository {
    public List<PlayerMarketActivity> page(Connection c, UUID playerId, int offset, int limit) {
        String sql = "SELECT * FROM ("
                + "SELECT 'ORDER' kind, order_id entity_id, order_id tie_key, "
                + "CASE WHEN status='MANUAL_REVIEW' THEN 1 ELSE 2 END priority, "
                + "side, status order_status, NULL delivery_type, item_blob, item_codec_version, "
                + "item_hash, item_registry_id, item_display_name, item_search_name, item_snapshot_qty, "
                + "price_per_unit, original_quantity, remaining_quantity, filled_quantity, updated_at sort_time "
                + "FROM auction_orders WHERE owner_uuid=? AND status IN ('ACTIVE','MANUAL_REVIEW') "
                + "UNION ALL "
                + "SELECT 'DELIVERY' kind, CAST(delivery_id AS TEXT) entity_id, "
                + "printf('%020d', delivery_id) tie_key, 0 priority, "
                + "NULL side, NULL order_status, delivery_type, item_blob, item_codec_version, "
                + "item_hash, item_registry_id, item_display_name, item_search_name, quantity item_snapshot_qty, "
                + "0 price_per_unit, quantity original_quantity, 0 remaining_quantity, quantity filled_quantity, "
                + "COALESCE(claimable_at, created_at) sort_time "
                + "FROM auction_deliveries WHERE player_uuid=? AND state='CLAIMABLE'"
                + ") activity ORDER BY priority, sort_time DESC, tie_key DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, playerId.toString());
            ps.setInt(3, Math.max(1, limit));
            ps.setInt(4, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<PlayerMarketActivity> result = new ArrayList<>();
                while (rs.next()) result.add(map(rs));
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("player market activity page failed", e);
        }
    }

    private static PlayerMarketActivity map(ResultSet rs) throws SQLException {
        ItemSnapshot item = new ItemSnapshot(
                rs.getBytes("item_blob"), rs.getString("item_codec_version"), rs.getString("item_hash"),
                rs.getString("item_registry_id"), rs.getString("item_display_name"),
                rs.getString("item_search_name"), rs.getInt("item_snapshot_qty"));
        boolean order = "ORDER".equals(rs.getString("kind"));
        return new PlayerMarketActivity(
                order ? PlayerMarketActivity.Kind.ORDER : PlayerMarketActivity.Kind.DELIVERY,
                order ? UUID.fromString(rs.getString("entity_id")) : null,
                order ? 0L : Long.parseLong(rs.getString("entity_id")),
                order ? OrderSide.valueOf(rs.getString("side")) : null,
                order ? OrderStatus.valueOf(rs.getString("order_status")) : null,
                order ? null : DeliveryType.valueOf(rs.getString("delivery_type")),
                item, rs.getLong("price_per_unit"), rs.getInt("original_quantity"),
                rs.getInt("remaining_quantity"), rs.getInt("filled_quantity"), rs.getLong("sort_time"));
    }
}

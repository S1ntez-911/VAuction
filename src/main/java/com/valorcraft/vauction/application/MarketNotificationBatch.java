package com.valorcraft.vauction.application;

import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.trade.Trade;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure per-player aggregation keyed by the player's order, never by a mixed latest trade. */
final class MarketNotificationBatch {
    static final class OrderBatch {
        private final UUID orderId;
        private final OrderSide side;
        private long quantity;
        private int fills;

        private OrderBatch(UUID orderId, OrderSide side) {
            this.orderId = orderId;
            this.side = side;
        }

        UUID orderId() { return orderId; }
        OrderSide side() { return side; }
        long quantity() { return quantity; }
        int fills() { return fills; }

        private void add(int added) {
            quantity += added;
            fills++;
        }
    }

    record Cursor(long settledAt, String tradeId) {}

    private final Map<UUID, OrderBatch> orders = new LinkedHashMap<>();
    private long newestTradeAt;
    private String newestTradeId = "";

    void add(Trade trade, OrderSide playerSide) {
        UUID orderId = playerSide == OrderSide.BUY ? trade.buyOrderId() : trade.sellOrderId();
        orders.computeIfAbsent(orderId, ignored -> new OrderBatch(orderId, playerSide))
                .add(trade.quantity());
        long at = trade.settledAt() == null ? trade.createdAt() : trade.settledAt();
        String id = trade.tradeId().toString();
        if (at > newestTradeAt || (at == newestTradeAt && id.compareTo(newestTradeId) > 0)) {
            newestTradeAt = at;
            newestTradeId = id;
        }
    }

    Collection<OrderBatch> orders() {
        return List.copyOf(orders.values());
    }

    boolean hasPurchases() {
        return orders.values().stream().anyMatch(batch -> batch.side() == OrderSide.BUY);
    }

    Cursor cursor() {
        return new Cursor(newestTradeAt, newestTradeId);
    }
}

package com.valorcraft.vauction.application;

import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.trade.Trade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketNotificationBatchTest {

    @Test
    void mixedBuyAndSellStayInIndependentOrderBatches() {
        UUID player = UUID.randomUUID();
        UUID copperBuy = UUID.randomUUID();
        UUID steelSell = UUID.randomUUID();
        Trade boughtCopper = trade(copperBuy, UUID.randomUUID(), player, UUID.randomUUID(),
                10, 100, "copper");
        Trade soldSteel = trade(UUID.randomUUID(), steelSell, UUID.randomUUID(), player,
                20, 200, "steel");
        MarketNotificationBatch batch = new MarketNotificationBatch();

        batch.add(boughtCopper, OrderSide.BUY);
        batch.add(soldSteel, OrderSide.SELL);

        List<MarketNotificationBatch.OrderBatch> lines = List.copyOf(batch.orders());
        assertEquals(2, lines.size());
        assertEquals(copperBuy, lines.get(0).orderId());
        assertEquals(OrderSide.BUY, lines.get(0).side());
        assertEquals(10, lines.get(0).quantity());
        assertEquals(steelSell, lines.get(1).orderId());
        assertEquals(OrderSide.SELL, lines.get(1).side());
        assertEquals(20, lines.get(1).quantity());
        assertTrue(batch.hasPurchases());
        assertEquals(200, batch.cursor().settledAt());
        assertEquals(soldSteel.tradeId().toString(), batch.cursor().tradeId());
    }

    @Test
    void multipleFillsOfOneOrderAggregateIntoOneLineAndKeepNewestCursor() {
        UUID player = UUID.randomUUID();
        UUID buyOrder = UUID.randomUUID();
        MarketNotificationBatch batch = new MarketNotificationBatch();
        Trade first = trade(buyOrder, UUID.randomUUID(), player, UUID.randomUUID(),
                1, 100, "copper");
        Trade second = trade(buyOrder, UUID.randomUUID(), player, UUID.randomUUID(),
                2, 101, "copper");

        batch.add(first, OrderSide.BUY);
        batch.add(second, OrderSide.BUY);

        MarketNotificationBatch.OrderBatch line = batch.orders().iterator().next();
        assertEquals(1, batch.orders().size());
        assertEquals(3, line.quantity());
        assertEquals(2, line.fills());
        assertEquals(101, batch.cursor().settledAt());
        assertEquals(second.tradeId().toString(), batch.cursor().tradeId());
    }

    @Test
    void twoBuyOrdersForDifferentItemsRemainTwoLines() {
        UUID player = UUID.randomUUID();
        MarketNotificationBatch batch = new MarketNotificationBatch();
        Trade copper = trade(UUID.randomUUID(), UUID.randomUUID(), player, UUID.randomUUID(),
                10, 100, "copper");
        Trade iron = trade(UUID.randomUUID(), UUID.randomUUID(), player, UUID.randomUUID(),
                20, 101, "iron");

        batch.add(copper, OrderSide.BUY);
        batch.add(iron, OrderSide.BUY);

        assertEquals(2, batch.orders().size());
        assertEquals(List.of(10L, 20L), batch.orders().stream()
                .map(MarketNotificationBatch.OrderBatch::quantity).toList());
    }

    private static Trade trade(UUID buyOrder, UUID sellOrder, UUID buyer, UUID seller,
                               int quantity, long settledAt, String market) {
        return Trade.newTrade(market, buyOrder, sellOrder, OrderSide.SELL,
                        10, quantity, 0, buyer, seller, "ref-" + UUID.randomUUID(), settledAt - 1)
                .tradeId(UUID.randomUUID()).build().settled(settledAt);
    }
}

package com.valorcraft.vauction.domain.market;

/**
 * Агрегированная сводка по рынку для будущего GUI.
 */
public record MarketSummary(
        String marketKey,
        String displayItem,
        long bestBid,
        long bestAsk,
        long availableBuyQuantity,
        long availableSellQuantity,
        long lastTradePrice
) {
}
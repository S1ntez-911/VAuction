package com.valorcraft.vauction.domain.market;

/**
 * Один уровень стакана (цена → суммарный остаток).
 */
public record OrderBookLevel(long pricePerUnit, long quantity) {
}
package com.valorcraft.vauction.domain.market;

import com.valorcraft.vauction.item.ItemSnapshot;

/** One bounded home/search result, including a server-owned visual snapshot. */
public record MarketCard(MarketSummary summary, ItemSnapshot visual) {
}

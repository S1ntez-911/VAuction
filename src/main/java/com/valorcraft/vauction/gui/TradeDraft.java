package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.domain.order.OrderSide;
import net.minecraft.world.item.ItemStack;

/**
 * Lightweight copy of an open trade state that survives the chest GUI closing.
 * "Точно" (exact quantity/price) stores a draft and asks the player to type
 * {@code /ah quantity <n>} / {@code /ah price <n>}; the command then re-opens
 * the same trade screen with the same item, side, mode, origin and TTL draft.
 * Drafts are short-lived so stale state never lingers.
 */
final class TradeDraft {
    static final long TTL_MILLIS = 5 * 60 * 1000L;

    final ItemStack unit;
    final OrderSide side;
    boolean immediate;
    final boolean searchActive;
    final String search;
    final int page;
    int quantity;
    long price;
    private final long createdAt = System.currentTimeMillis();

    private TradeDraft(ItemStack unit, OrderSide side, boolean immediate, int quantity,
                       long price, boolean searchActive, String search, int page) {
        this.unit = unit.copy();
        this.side = side;
        this.immediate = immediate;
        this.quantity = quantity;
        this.price = price;
        this.searchActive = searchActive;
        this.search = search;
        this.page = page;
    }

    static TradeDraft of(MarketSession session) {
        return new TradeDraft(session.unit, session.orderSide, session.immediate,
                session.quantity, session.price, session.searchActive, session.search,
                session.cataloguePage);
    }

    boolean expired() {
        return System.currentTimeMillis() - createdAt > TTL_MILLIS;
    }
}
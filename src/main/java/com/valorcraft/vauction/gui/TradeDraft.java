package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.domain.order.OrderSide;
import net.minecraft.world.item.ItemStack;

/**
 * Lightweight copy of an open trade state that survives the chest GUI closing.
 * «Другое» (custom quantity) and «Изменить цену» (custom price) store a draft
 * and ask the player to type {@code /ah set <число>}; the single contextual
 * command reads {@link #expectedInput} and applies either the QUANTITY or the
 * PRICE field, then re-opens the same trade screen with the same item, side,
 * mode, origin and TTL draft. Drafts are short-lived so stale state never lingers.
 */
final class TradeDraft {
    enum InputTarget {
        QUANTITY, PRICE
    }

    static final long TTL_MILLIS = 5 * 60 * 1000L;

    final ItemStack unit;
    final OrderSide side;
    boolean immediate;
    final boolean searchActive;
    final String search;
    final int page;
    int quantity;
    long price;
    final InputTarget expectedInput;
    private final long createdAt = System.currentTimeMillis();

    private TradeDraft(ItemStack unit, OrderSide side, boolean immediate, int quantity,
                       long price, boolean searchActive, String search, int page,
                       InputTarget expectedInput) {
        this.unit = unit.copy();
        this.side = side;
        this.immediate = immediate;
        this.quantity = quantity;
        this.price = price;
        this.searchActive = searchActive;
        this.search = search;
        this.page = page;
        this.expectedInput = expectedInput;
    }

    static TradeDraft of(MarketSession session, InputTarget expectedInput) {
        return new TradeDraft(session.unit, session.orderSide, session.immediate,
                session.quantity, session.price, session.searchActive, session.search,
                session.cataloguePage, expectedInput);
    }

    boolean expired() {
        return System.currentTimeMillis() - createdAt > TTL_MILLIS;
    }
}
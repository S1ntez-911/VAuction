package com.valorcraft.vauction.gui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * Central UI-safe icon palette. Only hand-picked vanilla items that do not grow
 * TerraFirmaGreg / GregTech material tooltips (dusts, ingots, ores, dyes and food
 * are banned) may become GUI buttons. If an icon must change, change it here once.
 */
final class MarketIcons {
    private MarketIcons() {}

    static final Item BACK = Items.ARROW;
    static final Item PAGE_PREVIOUS = Items.ARROW;
    static final Item PAGE_NEXT = Items.ARROW;
    static final Item SEARCH = Items.COMPASS;
    static final Item INFO_BOOK = Items.BOOK;
    static final Item CATALOGUE = Items.CHEST;
    static final Item MY = Items.ENDER_CHEST;
    static final Item PRIMARY_BUY = Items.EMERALD;
    static final Item PRIMARY_SELL = Items.HOPPER;
    static final Item MODE_SWITCH = Items.CLOCK;
    static final Item PRICE_INFO = Items.COMPARATOR;
    static final Item SUBMIT_LIMIT = Items.WRITABLE_BOOK;
    static final Item EXACT = Items.WRITABLE_BOOK;
    static final Item ALL = Items.BARREL;
    static final Item WARN_CONFIRM = Items.YELLOW_CONCRETE;
    static final Item CANCEL = Items.RED_CONCRETE;
    static final Item DISABLED = Items.BARRIER;

    /** Raw-material families that must never appear as GUI buttons on the TFG client. */
    static final Set<Item> FORBIDDEN = Set.of(
            Items.GLOWSTONE_DUST, Items.REDSTONE, Items.RED_DYE, Items.LIME_DYE,
            Items.GOLD_INGOT, Items.IRON_INGOT, Items.COPPER_INGOT, Items.NETHERITE_INGOT,
            Items.GOLD_NUGGET, Items.IRON_NUGGET, Items.RAW_GOLD, Items.RAW_IRON,
            Items.RAW_COPPER, Items.DIAMOND, Items.DIAMOND_BLOCK);
}
package com.valorcraft.vauction.gui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Small, fixed catalogue categories backed by bounded search aliases. */
enum MarketFilter {
    ALL("", "filter.all", Items.CHEST),
    RESOURCES("resources", "filter.resources", Items.BARREL),
    FOOD("food", "filter.food", Items.BOWL),
    TOOLS("tools", "filter.tools", Items.ANVIL),
    MACHINES("machines", "filter.machines", Items.PISTON);

    final String query;
    final String textKey;
    final Item icon;

    MarketFilter(String query, String textKey, Item icon) {
        this.query = query;
        this.textKey = textKey;
        this.icon = icon;
    }

    static MarketFilter byOrdinal(int ordinal) {
        MarketFilter[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ALL;
    }
}

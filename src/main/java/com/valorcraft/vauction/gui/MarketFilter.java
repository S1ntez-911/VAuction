package com.valorcraft.vauction.gui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Fixed player-facing categories backed by real ItemStack classification. */
enum MarketFilter {
    ALL(null, "filter.all", Items.CHEST),
    RESOURCES("resources", "filter.resources", Items.BARREL),
    FOOD("food", "filter.food", Items.BOWL),
    TOOLS("tools", "filter.tools", Items.ANVIL),
    MACHINES("machines", "filter.machines", Items.PISTON);

    final String category;
    final String textKey;
    final Item icon;

    MarketFilter(String category, String textKey, Item icon) {
        this.category = category;
        this.textKey = textKey;
        this.icon = icon;
    }

    static MarketFilter byOrdinal(int ordinal) {
        MarketFilter[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ALL;
    }
}

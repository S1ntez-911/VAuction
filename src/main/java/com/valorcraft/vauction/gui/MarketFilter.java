package com.valorcraft.vauction.gui;

/** Fixed player-facing categories backed by ItemStack classification. */
enum MarketFilter {
    ALL(null, "filter.all"),
    RESOURCES("resources", "filter.resources"),
    FOOD("food", "filter.food"),
    TOOLS("tools", "filter.tools"),
    MACHINES("machines", "filter.machines"),
    OTHER("other", "filter.other");

    final String category;
    final String textKey;

    MarketFilter(String category, String textKey) {
        this.category = category;
        this.textKey = textKey;
    }
}

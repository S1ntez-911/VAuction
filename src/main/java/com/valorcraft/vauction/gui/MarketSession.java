package com.valorcraft.vauction.gui;

import net.minecraft.world.SimpleContainer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class MarketSession {
    final UUID playerId;
    final Map<Integer, GuiAction> actions = new HashMap<>();
    final Map<String, String> placeholders = new LinkedHashMap<>();
    MarketScreen screen = MarketScreen.BROWSE;
    int cataloguePage;
    String search = "";
    boolean mineOnly;
    boolean transitioning;
    boolean executing;
    int containerId = -1;
    MarketFilter filter = MarketFilter.ALL;
    SimpleContainer contents;
    ServerChestMenu menu;
    MarketScreen openScreen;
    int openRows;

    MarketSession(UUID playerId) {
        this.playerId = playerId;
    }

    void resetActions() {
        actions.clear();
    }
}

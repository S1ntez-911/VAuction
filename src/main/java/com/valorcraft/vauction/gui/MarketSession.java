package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.application.AuctionReadService;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class MarketSession {
    final UUID playerId;
    final Map<Integer, GuiAction> actions = new HashMap<>();
    MarketScreen screen = MarketScreen.HOME;
    int page;
    String search = "";
    ItemStack unit = ItemStack.EMPTY;
    String marketKey;
    int inventorySlot = -1;
    OrderSide orderSide;
    int quantity = 1;
    long price = 1;
    UUID pendingRequestId;
    UUID pendingCancelId;
    int containerId = -1;
    boolean transitioning;
    boolean executing;
    boolean immediate;
    AuctionReadService.ImmediateQuote quote;
    SimpleContainer contents;
    ServerChestMenu menu;

    MarketSession(UUID playerId) {
        this.playerId = playerId;
    }

    void resetActions() {
        actions.clear();
    }
}

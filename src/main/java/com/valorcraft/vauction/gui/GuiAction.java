package com.valorcraft.vauction.gui;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

record GuiAction(Type type, int number, long amount, UUID orderId, long deliveryId,
                 ItemStack item) {
    enum Type {
        HOME, BROWSE, HELP, SEARCH_HELP, PICKER, ORDERS, DELIVERIES, OPEN_MARKET, PAGE, REFRESH,
        BUY_NOW, SELL_NOW, BUY, SELL, ADJUST_QUANTITY, ADJUST_PRICE_PERCENT, BEST_PRICE,
        REVIEW, CONFIRM_IMMEDIATE, CONFIRM_ORDER, PREPARE_CANCEL, CONFIRM_CANCEL, CLAIM, BACK
    }

    static GuiAction simple(Type type) {
        return new GuiAction(type, 0, 0, null, 0, ItemStack.EMPTY);
    }

    static GuiAction number(Type type, int value) {
        return new GuiAction(type, value, 0, null, 0, ItemStack.EMPTY);
    }

    static GuiAction market(ItemStack item) {
        return new GuiAction(Type.OPEN_MARKET, 0, 0, null, 0, item.copy());
    }

    static GuiAction order(Type type, UUID orderId) {
        return new GuiAction(type, 0, 0, orderId, 0, ItemStack.EMPTY);
    }

    static GuiAction delivery(long deliveryId) {
        return new GuiAction(Type.CLAIM, 0, 0, null, deliveryId, ItemStack.EMPTY);
    }
}

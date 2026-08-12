package com.valorcraft.vauction.gui;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

record GuiAction(Type type, int number, long amount, UUID orderId, long deliveryId,
                 ItemStack item) {
    enum Type {
        HOME, BROWSE, REFRESH, HELP, SEARCH_HELP, MY, OPEN_PRODUCT, OPEN_FILTERS, PAGE, FILTER,
        BUY_NOW, SELL_NOW, BUY, SELL, ADJUST_QUANTITY, SET_QUANTITY, SET_MAX_QUANTITY,
        REVIEW, CONFIRM_IMMEDIATE, CONFIRM_ORDER, MANAGE_ORDER, PREPARE_CANCEL, CONFIRM_CANCEL, CLAIM, BACK,
        EXACT_QUANTITY, EXACT_PRICE
    }

    static GuiAction simple(Type type) {
        return new GuiAction(type, 0, 0, null, 0, ItemStack.EMPTY);
    }

    static GuiAction number(Type type, int value) {
        return new GuiAction(type, value, 0, null, 0, ItemStack.EMPTY);
    }

    static GuiAction quantityPreset(int quantity) {
        return number(Type.SET_QUANTITY, quantity);
    }

    static GuiAction product(ItemStack item) {
        return new GuiAction(Type.OPEN_PRODUCT, 0, 0, null, 0, item.copy());
    }

    static GuiAction order(Type type, UUID orderId) {
        return new GuiAction(type, 0, 0, orderId, 0, ItemStack.EMPTY);
    }

    static GuiAction delivery(long deliveryId) {
        return new GuiAction(Type.CLAIM, 0, 0, null, deliveryId, ItemStack.EMPTY);
    }

    static GuiAction manage(UUID orderId, ItemStack item, boolean buy, int remaining, long price) {
        return new GuiAction(Type.MANAGE_ORDER, buy ? remaining : -remaining,
                price, orderId, 0, item.copy());
    }
}

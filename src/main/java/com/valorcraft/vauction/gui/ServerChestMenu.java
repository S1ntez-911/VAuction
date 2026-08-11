package com.valorcraft.vauction.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** Vanilla GENERIC_9x1..9x6 protocol with every client-side move denied server-side. */
final class ServerChestMenu extends ChestMenu {
    private final MarketController controller;
    private final MarketSession session;

    ServerChestMenu(int containerId, Inventory inventory, Container contents,
                    int rows, MarketController controller, MarketSession session) {
        super(type(rows), containerId, inventory, contents, rows);
        this.controller = controller;
        this.session = session;
    }

    private static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Chest rows must be between 1 and 6: " + rows);
        };
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        controller.clicked(player, session, slotId, button, clickType);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotId) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(net.minecraft.world.inventory.Slot slot) {
        return false;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, net.minecraft.world.inventory.Slot slot) {
        return false;
    }
}

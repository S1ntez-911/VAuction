package com.valorcraft.vauction.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** Vanilla GENERIC_9x6 protocol with every client-side move denied server-side. */
final class ServerChestMenu extends ChestMenu {
    private final MarketController controller;
    private final MarketSession session;

    ServerChestMenu(int containerId, Inventory inventory, Container contents,
                    MarketController controller, MarketSession session) {
        super(MenuType.GENERIC_9x6, containerId, inventory, contents, 6);
        this.controller = controller;
        this.session = session;
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

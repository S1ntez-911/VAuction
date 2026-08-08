package com.valorcraft.vauction.application;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Продовая реализация {@link InventoryOps} над инвентором игрока.
 * Вызовы только с серверного потока.
 * <p>
 * {@link #give}: выдаём только если весь стек помещается (нет частичных выдач —
 * delivery забирается за один приём либо не забирается вовсе).
 * {@link #tryTake}: ничего не списываем, если предметов не хватает.
 */
public final class ServerInventoryOps implements InventoryOps {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    private final Supplier<MinecraftServer> server;

    public ServerInventoryOps(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public boolean tryTake(UUID playerId, ItemStack unit, int quantity) {
        ServerPlayer player = server.get().getPlayerList() == null
                ? null : server.get().getPlayerList().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        Inventory inv = player.getInventory();
        long available = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, unit)) {
                available += s.getCount();
                if (available >= quantity) {
                    break;
                }
            }
        }
        if (available < quantity) {
            return false;
        }
        int remaining = quantity;
        for (int slot = 0; slot < inv.getContainerSize() && remaining > 0; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty() || !ItemStack.isSameItemSameTags(s, unit)) {
                continue;
            }
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
        }
        return true;
    }

    @Override
    public ItemStack give(UUID playerId, ItemStack stack) {
        ServerPlayer player = server.get() == null || server.get().getPlayerList() == null
                ? null : server.get().getPlayerList().getPlayer(playerId);
        if (player == null) {
            return stack;
        }
        if (!fitsEntirely(player.getInventory(), stack)) {
            return stack;
        }
        boolean added = player.getInventory().add(stack);
        ItemStack leftover = added ? ItemStack.EMPTY : stack;
        if (!leftover.isEmpty()) {
            LOGGER.warn("give({}) остался остаток после add: {}", playerId, leftover);
        }
        return leftover;
    }

    private static boolean fitsEntirely(Inventory inv, ItemStack stack) {
        ItemStack probe = stack.copy();
        for (int slot = 0; slot < inv.getContainerSize() && !probe.isEmpty(); slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty()) {
                probe.shrink(Math.min(probe.getCount(), probe.getMaxStackSize()));
            } else if (ItemStack.isSameItemSameTags(s, probe) && s.getCount() < s.getMaxStackSize()) {
                int space = s.getMaxStackSize() - s.getCount();
                probe.shrink(Math.min(space, probe.getCount()));
            }
        }
        return probe.isEmpty();
    }
}
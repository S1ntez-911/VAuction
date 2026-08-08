package com.valorcraft.exchange.screen;

import com.valorcraft.exchange.ExchangeMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Контейнерное меню биржи: только просмотр, слоты инвентаря игрока не используются.
 * <p>
 * Все данные (лоты, заявки, почта, история) приходят кастомными пакетами, все действия
 * уходят пакетами клиент→сервер. Меню нужно лишь для корректного открытия экрана
 * и обработки закрытия (для очистки серверных данных сессии).
 */
public class ExchangeContainerMenu extends AbstractContainerMenu {

    private final Player player;

    // Конструкция клиентской стороны (network-декод).
    public ExchangeContainerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        this(containerId, playerInventory);
    }

    public ExchangeContainerMenu(int containerId, Inventory playerInventory) {
        super(ExchangeMod.EXCHANGE_MENU.get(), containerId);
        this.player = playerInventory.player;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return true;
    }

    public Player getPlayer() {
        return player;
    }
}
package com.valorcraft.exchange.event;

import com.valorcraft.exchange.ExchangeMod;
import com.valorcraft.exchange.data.BuyOrder;
import com.valorcraft.exchange.data.ExchangeDataManager;
import com.valorcraft.exchange.data.ExchangeLogEntry;
import com.valorcraft.exchange.data.ExchangeTransactionType;
import com.valorcraft.exchange.data.SellOrder;
import com.valorcraft.exchange.exchange.ExchangeService;
import com.valorcraft.exchange.integration.VEconomyIntegration;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * Серверные события биржи: уведомление о почте при входе, периодическая проверка
 * просрочки лотов/заявок (каждые 5 минут), принудительное сохранение при остановке.
 */
public class ServerEventHandler {

    private int ticksUntilExpiryCheck = 5 * 20 * 60;

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (ExchangeService.get().hasMail(player)) {
                player.sendSystemMessage(Component.literal(
                        "[Биржа] У вас есть посылки на почте. Заберите: /exchange или /birge -> Почта"));
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // mailbox flush не требуется — данные хранятся в Saved Data и сохраняются сервером.
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (--ticksUntilExpiryCheck > 0) {
            return;
        }
        ticksUntilExpiryCheck = 5 * 20 * 60;
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            ExchangeDataManager data = ExchangeDataManager.get(server.overworld());
            expiryCheck(server, data);
        } catch (Exception e) {
            ExchangeMod.LOGGER.warn("Биржа: ошибка expiry-проверки", e);
        }
    }

    private void expiryCheck(MinecraftServer server, ExchangeDataManager data) {
        long now = System.currentTimeMillis();
        int sellDays = com.valorcraft.exchange.config.ExchangeConfig.sellOrderExpiryDays();
        int buyDays = com.valorcraft.exchange.config.ExchangeConfig.buyOrderExpiryDays();

        if (sellDays > 0) {
            for (SellOrder order : data.sellOrders()) {
                if (now - order.createdAt() > sellDays * 86400000L) {
                    ExchangeService.addItemsToInventoryOrMailboxByUuid(
                            server, order.sellerUUID(), order.sample(), order.remainingQuantity());
                    data.removeSellOrder(order.id());
                    data.note(new ExchangeLogEntry(now, ExchangeTransactionType.CANCEL,
                            order.sellerUUID(), null, order.itemName(), order.remainingQuantity(), 0));
                }
            }
        }

        if (buyDays > 0) {
            for (BuyOrder order : data.buyOrders()) {
                if (order.active() && now - order.createdAt() > buyDays * 86400000L) {
                    // возврат оставшейся заморозки покупателю
                    long remainingFunds = order.pricePerUnit() * (long) order.remaining();
                    boolean refunded = false;
                    if (remainingFunds > 0) {
                        refunded = VEconomyIntegration.unfreezeRefund(
                                "exchange:buy:" + order.id() + ":" + order.refEpoch(),
                                ExchangeService.reason("истечение заявки #" + order.id()),
                                order.id() + ":expire:" + order.refEpoch());
                    }
                    data.removeBuyOrder(order.id());
                    data.recalcFrozen(order.buyerUUID());
                    data.note(new ExchangeLogEntry(now, ExchangeTransactionType.CANCEL,
                            null, order.buyerUUID(), order.itemName(), order.remaining(),
                            refunded ? remainingFunds : 0));
                }
            }
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        if (server != null) {
            ExchangeDataManager data = ExchangeDataManager.get(server.overworld());
            data.setDirty();
        }
    }
}
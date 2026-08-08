package com.valorcraft.exchange.network;

import com.valorcraft.exchange.data.BuyOrder;
import com.valorcraft.exchange.data.ExchangeDataManager;
import com.valorcraft.exchange.data.SellOrder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Сборка и отправка полной синхронизации рынка конкретному игроку.
 */
public final class MarketSyncHelper {

    private MarketSyncHelper() {}

    public static void sendFullSync(ServerPlayer player) {
        ExchangeDataManager data = ExchangeDataManager.get(player.serverLevel());
        List<SyncMarketPacket.SellEntry> sells = new ArrayList<>();
        for (SellOrder o : data.sellOrders()) {
            sells.add(new SyncMarketPacket.SellEntry(o.id(), o.sample(), o.pricePerUnit(),
                    o.remainingQuantity()));
        }
        List<SyncMarketPacket.BuyEntry> buys = new ArrayList<>();
        for (BuyOrder o : data.buyOrders()) {
            buys.add(new SyncMarketPacket.BuyEntry(o.id(), o.sample(), o.pricePerUnit(),
                    o.totalRequested(), o.fulfilledAmount()));
        }
        List<net.minecraft.world.item.ItemStack> mailbox =
                new ArrayList<>(data.mailbox(player.getUUID()));
        long balance = com.valorcraft.exchange.integration.VEconomyIntegration.getBalance(player.getUUID());
        ModNetworking.channel().send(PacketDistributor.PLAYER.with(() -> player),
                new SyncMarketPacket(sells, buys, mailbox, balance));
    }
}
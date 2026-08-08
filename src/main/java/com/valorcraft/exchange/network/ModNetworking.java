package com.valorcraft.exchange.network;

import com.valorcraft.exchange.ExchangeMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Сетевой канал биржи. Все пакеты — действия GUI (клиент → сервер) и
 * синхронизация рынка (сервер → клиент). Протокол версионирован.
 */
public final class ModNetworking {

    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ExchangeMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int index = 0;

    private ModNetworking() {}

    public static SimpleChannel channel() {
        return CHANNEL;
    }

    public static void register() {
        CHANNEL.messageBuilder(ExchangeActionPacket.class, index++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ExchangeActionPacket::encode)
                .decoder(ExchangeActionPacket::new)
                .consumerMainThread(ExchangeActionPacket::handle)
                .add();
        CHANNEL.messageBuilder(SyncMarketPacket.class, index++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncMarketPacket::encode)
                .decoder(SyncMarketPacket::new)
                .consumerMainThread(SyncMarketPacket::handle)
                .add();
    }
}

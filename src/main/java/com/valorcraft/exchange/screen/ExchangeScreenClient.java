package com.valorcraft.exchange.screen;

import com.valorcraft.exchange.network.SyncMarketPacket;

import javax.annotation.Nullable;

/**
 * Клиентское хранилище последней синхронизации рынка для открытого экрана.
 */
public final class ExchangeScreenClient {

    @Nullable
    private static SyncMarketPacket current;

    private ExchangeScreenClient() {}

    public static void receiveMarket(SyncMarketPacket packet) {
        current = packet;
    }

    @Nullable
    public static SyncMarketPacket current() {
        return current;
    }

    public static void clear() {
        current = null;
    }
}
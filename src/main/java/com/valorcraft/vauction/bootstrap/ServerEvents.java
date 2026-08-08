package com.valorcraft.vauction.bootstrap;

import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

/**
 * Жизненный цикл модуля на FORGE-шине (события сервера).
 * БД живёт в каталоге мира: <world>/vauction/auction.db.
 */
@Mod.EventBusSubscriber(modid = VAuctionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {

    private ServerEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Path worldRoot = event.getServer().getWorldPath(LevelResource.ROOT);
        Path dbPath = worldRoot.resolve("vauction").resolve("auction.db");
        VAuctionCore.start(dbPath);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        VAuctionCore.shutdown();
    }
}
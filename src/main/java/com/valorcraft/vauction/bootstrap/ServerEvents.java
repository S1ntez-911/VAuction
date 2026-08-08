package com.valorcraft.vauction.bootstrap;

import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

/**
 * Жизненный цикл модуля на FORGE-шине (события сервера).
 * БД живёт в каталоге мира: <world>/vauction/auction.db.
 */
@Mod.EventBusSubscriber(modid = VAuctionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {

    private static final int EXPIRY_INTERVAL_TICKS = 20 * 60;
    private static final int RECOVERY_BASE_TICKS = 20 * 30;
    private static final int RECOVERY_MAX_TICKS = 20 * 300;
    private static int ticksUntilExpiry = EXPIRY_INTERVAL_TICKS;
    private static int recoveryIntervalTicks = RECOVERY_BASE_TICKS;
    private static int ticksUntilRecovery = RECOVERY_BASE_TICKS;

    private ServerEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Path worldRoot = event.getServer().getWorldPath(LevelResource.ROOT);
        Path dbPath = worldRoot.resolve("vauction").resolve("auction.db");
        VAuctionCore.start(dbPath, event.getServer());
        ticksUntilExpiry = EXPIRY_INTERVAL_TICKS;
        recoveryIntervalTicks = RECOVERY_BASE_TICKS;
        ticksUntilRecovery = RECOVERY_BASE_TICKS;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        VAuctionCore.shutdown();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !VAuctionCore.instance().isRunning()) {
            return;
        }
        if (--ticksUntilExpiry <= 0) {
            ticksUntilExpiry = EXPIRY_INTERVAL_TICKS;
            VAuctionCore.instance().auctionService().expirePass(System.currentTimeMillis());
        }
        if (--ticksUntilRecovery <= 0) {
            var report = VAuctionCore.instance().recoveryService().scan();
            recoveryIntervalTicks = report.total() > 0
                    ? RECOVERY_BASE_TICKS
                    : Math.min(RECOVERY_MAX_TICKS, recoveryIntervalTicks * 2);
            ticksUntilRecovery = recoveryIntervalTicks;
        }
    }
}

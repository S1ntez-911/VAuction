package com.valorcraft.vauction.bootstrap;

import com.valorcraft.vauction.application.AuctionWorkLimits;
import com.valorcraft.vauction.application.WorkBudget;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Жизненный цикл модуля на FORGE-шине (события сервера).
 * БД живёт в каталоге мира: <world>/vauction/auction.db.
 */
@Mod.EventBusSubscriber(modid = VAuctionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");
    private static final int WORK_BUDGET_WARN_COOLDOWN_TICKS = 20 * 60;
    private static int ticksUntilExpiry = AuctionWorkLimits.EXPIRY_INTERVAL_TICKS;
    private static int recoveryIntervalTicks = AuctionWorkLimits.RECOVERY_BASE_TICKS;
    private static int ticksUntilRecovery = AuctionWorkLimits.RECOVERY_BASE_TICKS;
    private static int workBudgetWarnCooldown;

    private ServerEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(ServerStartedEvent event) {
        Path worldRoot = event.getServer().getWorldPath(LevelResource.ROOT);
        Path dbPath = worldRoot.resolve("vauction").resolve("auction.db");
        VAuctionCore.start(dbPath, event.getServer());
        ticksUntilExpiry = AuctionWorkLimits.EXPIRY_INTERVAL_TICKS;
        recoveryIntervalTicks = AuctionWorkLimits.RECOVERY_BASE_TICKS;
        ticksUntilRecovery = AuctionWorkLimits.RECOVERY_BASE_TICKS;
        workBudgetWarnCooldown = 0;
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
        WorkBudget budget = WorkBudget.timed(AuctionWorkLimits.MAX_SERVER_TICK_OPERATIONS,
                AuctionWorkLimits.MAX_MAINTENANCE_NANOS);
        if (--ticksUntilExpiry <= 0 && !budget.exhausted()) {
            var expiry = VAuctionCore.instance().auctionService()
                    .expireSlice(System.currentTimeMillis(), budget);
            ticksUntilExpiry = expiry.backlogRemaining()
                    ? 1 : AuctionWorkLimits.EXPIRY_INTERVAL_TICKS;
        }
        if (--ticksUntilRecovery <= 0 && !budget.exhausted()) {
            var report = VAuctionCore.instance().recoveryService().runtimeSlice(budget);
            recoveryIntervalTicks = report.backlogRemaining()
                    ? AuctionWorkLimits.RECOVERY_BASE_TICKS
                    : Math.min(AuctionWorkLimits.RECOVERY_MAX_TICKS, recoveryIntervalTicks * 2);
            ticksUntilRecovery = recoveryIntervalTicks;
        }
        if (!budget.exhausted()) {
            VAuctionCore.instance().auctionService().pumpMatching(budget,
                    AuctionWorkLimits.MAX_MATCH_FILLS_PER_PUMP);
        }
        if (!budget.exhausted()) {
            VAuctionCore.instance().auctionService().finishImmediateRemainders(budget, 16);
        }
        VAuctionCore.instance().notificationService().tick();
        if (workBudgetWarnCooldown > 0) {
            workBudgetWarnCooldown--;
        } else if (budget.exhausted()) {
            LOGGER.warn("VAuction maintenance reached its per-tick work/time budget; durable backlog will continue");
            workBudgetWarnCooldown = WORK_BUDGET_WARN_COOLDOWN_TICKS;
        }
    }
}

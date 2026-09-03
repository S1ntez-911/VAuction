package com.valorcraft.vauction;

import com.mojang.logging.LogUtils;
import com.valorcraft.vauction.command.AuctionCommands;
import com.valorcraft.vauction.config.AuctionConfig;
import com.valorcraft.vauction.persistence.AuctionStore;
import com.valorcraft.vauction.service.AuctionService;
import com.valorcraft.vauction.lang.AuctionLang;
import com.valorcraft.vauction.ui.AuctionMenu;
import com.valorcraft.veconomy.EconomyCore;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(VAuctionMod.MOD_ID)
public final class VAuctionMod {
    public static final String MOD_ID = "vauction";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final AuctionService SERVICE = new AuctionService(new AuctionStore());
    private boolean startupRecoveryPending;
    private long nextStartupRecoveryAttemptAt;

    public VAuctionMod() {
        try {
            Files.createDirectories(FMLPaths.CONFIGDIR.get().resolve("VMods").resolve("VAuction"));
        } catch (IOException e) {
            // Config registration may still succeed (or provide a more precise Forge error).
            // Never crash mod construction solely because this eager convenience mkdir failed.
            LOGGER.error("Не удалось заранее создать config/VMods/VAuction; VAuction будет отключён, если путь недоступен", e);
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AuctionConfig.SPEC,
                "VMods/VAuction/VAuction.toml");
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static AuctionService service() { return SERVICE; }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        AuctionLang.load();
        try {
            SERVICE.load();
            // VEconomy handles the same lifecycle event and may finish after VAuction.
            // Reconcile escrow on a later server tick instead of depending on listener order.
            startupRecoveryPending = true;
            nextStartupRecoveryAttemptAt = 0L;
            LOGGER.info("VAuction запущен");
        } catch (RuntimeException e) {
            LOGGER.error("VAuction отключён из-за ошибки хранилища; Minecraft-сервер продолжит работу", e);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !startupRecoveryPending || !SERVICE.isAvailable()) return;
        long now = System.currentTimeMillis();
        if (now < nextStartupRecoveryAttemptAt || !EconomyCore.isStarted()) return;
        try {
            SERVICE.recoverPending();
            startupRecoveryPending = false;
            LOGGER.info("Стартовое восстановление незавершённых покупок завершено");
        } catch (RuntimeException e) {
            // A temporary economy/database failure must not stop the Minecraft server.
            nextStartupRecoveryAttemptAt = now + 5_000L;
            LOGGER.error("Стартовое восстановление VAuction не завершено; повтор через 5 секунд", e);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            if (SERVICE.isAvailable()) SERVICE.claimOnLogin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        AuctionMenu.clearSavedState(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AuctionCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        startupRecoveryPending = false;
        SERVICE.close();
        LOGGER.info("VAuction остановлен");
    }
}

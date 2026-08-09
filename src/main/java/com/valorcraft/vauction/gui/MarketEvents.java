package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.bootstrap.VAuctionMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = VAuctionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MarketEvents {
    private MarketEvents() {}

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        MarketCommands.register(event);
    }

    @SubscribeEvent
    public static void closed(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MarketController.instance().closed(player.getUUID(), event.getContainer().containerId);
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        MarketController.instance().logout(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && com.valorcraft.vauction.bootstrap.VAuctionCore.instance().isRunning()) {
            com.valorcraft.vauction.bootstrap.VAuctionCore.instance()
                    .notificationService().playerLoggedIn(player);
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        MarketController.instance().clear();
        if (com.valorcraft.vauction.bootstrap.VAuctionCore.instance().isRunning()) {
            com.valorcraft.vauction.bootstrap.VAuctionCore.instance().notificationService().clear();
        }
    }
}

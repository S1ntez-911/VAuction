package com.valorcraft.exchange.client;

import com.valorcraft.exchange.ExchangeMod;
import com.valorcraft.exchange.screen.ExchangeScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Клиентская регистрация: привязка экрана к типу меню биржи.
 */
@Mod.EventBusSubscriber(modid = ExchangeMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ExchangeModClient {

    private ExchangeModClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() ->
                MenuScreens.register(ExchangeMod.EXCHANGE_MENU.get(), ExchangeScreen::new));
    }
}
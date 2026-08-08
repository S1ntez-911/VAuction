package com.valorcraft.exchange;

import com.valorcraft.exchange.command.ExchangeCommand;
import com.valorcraft.exchange.config.ExchangeConfig;
import com.valorcraft.exchange.event.ServerEventHandler;
import com.valorcraft.exchange.network.ModNetworking;
import com.valorcraft.exchange.screen.ExchangeContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ResourceExchange — серверная биржа ресурсов с нативной интеграцией VEconomy.
 * <p>
 * Все финансовые операции выполняются через {@code EconomyCore.api()}/{@code EconomyCore.escrow()}
 * VEconomy: списание, зачисление и заморозка средств. Мод жёстко требует VEconomy
 * (обязательная зависимость в mods.toml + проверка при старте), при его отсутствии —
 * аварийный останов загрузки.
 */
@Mod(ExchangeMod.MODID)
public final class ExchangeMod {

    public static final String MODID = "exchange_core";
    public static final String MOD_NAME = "ResourceExchange";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    /** Меню биржи (открывается командой на сервере, клиент лишь рисует экран). */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);

    public static final RegistryObject<MenuType<ExchangeContainerMenu>> EXCHANGE_MENU =
            MENUS.register("exchange", () -> IForgeMenuType.create(ExchangeContainerMenu::new));

    public ExchangeMod() {
        if (!ModList.get().isLoaded("economy_core")) {
            throw new IllegalStateException(MOD_NAME
                    + " требует мод VEconomy (economy_core). Установите VEconomy >= 1.0 на сервер.");
        }
        ExchangeConfig.register();

        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        MENUS.register(bus);

        ModNetworking.register();

        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.register(new ServerEventHandler());
        forgeBus.addListener(ExchangeCommand::register);

        ExchangeMod.LOGGER.info("{} загружается (серверная биржа ресурсов, VEconomy найден)", MOD_NAME);
    }
}
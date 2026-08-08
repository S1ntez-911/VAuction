package com.valorcraft.vauction.bootstrap;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Точка входа VAuction (серверный мод, Forge 1.20.1).
 * Мод загружается ПОСЛЕ economy_core (см. mods.toml dependency ordering="AFTER").
 * Клиенту мод не требуется (displayTest="IGNORE_SERVER_VERSION").
 */
@Mod(VAuctionMod.MODID)
public final class VAuctionMod {

    public static final String MODID = "vauction";
    public static final String MOD_NAME = "VAuction";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public VAuctionMod() {
        LOGGER.info("{} загружается (серверный аукцион, использует VEconomy)", MOD_NAME);
    }
}
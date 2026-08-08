package com.valorcraft.vauction.economy;

import com.valorcraft.veconomy.EconomyCore;
import net.minecraftforge.fml.ModList;

/**
 * Реализация {@link EconomyGateway} поверх публичного API VEconomy.
 * <p>
 * Используются ТОЛЬКО публичные точки: {@code EconomyCore.isStarted()/api()/escrow()}.
 * Никаких внутренних репозиториев/сервисов VEconomy (DatabaseManager, AccountRepository,
 * EscrowRepository и т.п.) не трогаем. На этом этапе достаточно проверки доступности.
 * <p>
 * Следующий этап: reserve/settle/release через {@code EconomyCore.escrow()}
 * (EscrowApi) — деньги для покупки/комиссий.
 */
public final class VEconomyGateway implements EconomyGateway {

    public static final String ECONOMY_MOD_ID = "economy_core";

    private final String modId;

    public VEconomyGateway() {
        this(ECONOMY_MOD_ID);
    }

    public VEconomyGateway(String modId) {
        this.modId = modId;
    }

    @Override
    public boolean isAvailable() {
        return isModLoaded() && isEconomyStarted();
    }

    private boolean isModLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isEconomyStarted() {
        try {
            return EconomyCore.isStarted()
                    && EconomyCore.api() != null
                    && EconomyCore.escrow() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String status() {
        return "economy_core loaded=" + isModLoaded() + ", economy started=" + isEconomyStarted();
    }
}
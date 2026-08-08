package com.valorcraft.vauction.item;

import net.minecraft.world.item.ItemStack;

/**
 * Стратегия вычисления маркет-ключа (fungibility-identity).
 * Отделена от matching-движка: смена стратегии не требует изменений в нём.
 */
public interface MarketKeyStrategy {

    /**
     * Вычислить ключ рынка для образца предмета. {@code count} не участвует
     * (внутри делается копия с count=1) — EXACT_STACK_EXCEPT_COUNT.
     */
    String keyOf(ItemStack stack) throws ItemCodecException;

    /** Префикс ключа (для отладки/индексов). */
    String prefix();
}
package com.valorcraft.vauction.application;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Порт инвентаря для аукциона. Бизнес-логика не зависит от Minecraft-игрока:
 * тесты используют фейк, продакшен — {@link ServerInventoryOps}.
 * Контракт «выдать» — атомарно: либо стек влезает целиком и выдаётся,
 * либо не выдаётся ничего (возврат == переданный стек).
 */
public interface InventoryOps {

    /** Снять {@code quantity} единиц {@code unit} из инвентаря (физическое списание). */
    boolean tryTake(UUID playerId, ItemStack unit, int quantity);

    /**
     * Сколько единиц {@code unit} доступно в инвентаре (по идентичным
     * стекам — без учёта занятости слотов). Используется для создания
     * sell-ордера из нескольких слотов сразу.
     */
    int availableCount(UUID playerId, ItemStack unit);

    /**
     * Выдать {@code stack} игроку.
     * @return пустой стек — выдано всё; иначе — стек целиком не поместился
     *         (ничего не выдано).
     */
    ItemStack give(UUID playerId, ItemStack stack);
}
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
     * Выдать {@code stack} игроку.
     * @return пустой стек — выдано всё; иначе — стек целиком не поместился
     *         (ничего не выдано).
     */
    ItemStack give(UUID playerId, ItemStack stack);
}
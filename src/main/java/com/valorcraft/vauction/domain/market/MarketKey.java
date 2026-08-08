package com.valorcraft.vauction.domain.market;

import java.util.Objects;

/**
 * Идентификатор рынка (fungibility): какие ItemStack считаются взаимозаменяемым
 * товаром. НЕ registryId и НЕ displayName — стабильный fingerprint предмета.
 * <p>
 * Для первого этапа стратегия {@code EXACT_STACK_EXCEPT_COUNT}: два ItemStack
 * взаимозаменяемы, если одинаковы предмет и данные (NBT, damage, enchant и т.д.),
 * а count не участвует. Позже добавляются другие стратегии нормализации без
 * переписывания matching-движка ({@link com.valorcraft.vauction.item.MarketKeyStrategy}).
 */
public record MarketKey(String value) {

    /** Префикс для точного ключа («exact…»). */
    public static final String EXACT_PREFIX = "exact:";

    public MarketKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > 191) {
            throw new IllegalArgumentException("market key must be 1..191 chars");
        }
    }

    public static MarketKey of(String value) {
        return new MarketKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
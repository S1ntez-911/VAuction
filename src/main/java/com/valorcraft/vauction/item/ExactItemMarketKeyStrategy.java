package com.valorcraft.vauction.item;

import com.valorcraft.vauction.domain.market.MarketKey;
import net.minecraft.world.item.ItemStack;

/**
 * Стратегия EXACT_STACK_EXCEPT_COUNT: ключ = SHA-256 канонической
 * сериализации стека с count=1. Одинаковые по предмету/данным стеки
 * (разный count) — один ключ; разное NBT/damage/enchant/энергия — разные ключи.
 * <p>
 * Безопасно по умолчанию: никакой «умной» очистки тегов — correctness first.
 */
public final class ExactItemMarketKeyStrategy implements MarketKeyStrategy {

    private final ItemStackCodec codec;

    public ExactItemMarketKeyStrategy(ItemStackCodec codec) {
        this.codec = codec;
    }

    @Override
    public String keyOf(ItemStack stack) throws ItemCodecException {
        if (stack == null || stack.isEmpty()) {
            throw new ItemCodecException(ItemCodecError.EMPTY_ITEM,
                    "market key requires a non-empty item");
        }
        ItemStack unit = stack.copy();
        unit.setCount(1);
        ItemSnapshot snapshot = codec.encode(unit);
        return prefix() + snapshot.hash();
    }

    @Override
    public String prefix() {
        return MarketKey.EXACT_PREFIX;
    }
}
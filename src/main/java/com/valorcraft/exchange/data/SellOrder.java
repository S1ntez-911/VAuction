package com.valorcraft.exchange.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Лот на продажу. {@code sample} — эталон предмета (количество 1, без лишних NBT).
 * {@code pricePerUnit} — в минимальных единицах валюты (minor units VEconomy).
 */
public record SellOrder(
        UUID id,
        UUID sellerUUID,
        ItemStack sample,
        long pricePerUnit,
        int totalQuantity,
        int remainingQuantity,
        long createdAt
) {

    public static final String TAG_ID = "id";
    public static final String TAG_SELLER = "seller";
    public static final String TAG_ITEM = "item";
    public static final String TAG_PRICE = "price";
    public static final String TAG_TOTAL = "totalQty";
    public static final String TAG_REMAINING = "remainingQty";
    public static final String TAG_CREATED = "createdAt";

    public static SellOrder create(UUID seller, ItemStack sample, long pricePerUnit, int quantity) {
        ItemStack copy = sample.copy();
        copy.setCount(1);
        return new SellOrder(UUID.randomUUID(), seller, copy, pricePerUnit, quantity, quantity,
                System.currentTimeMillis());
    }

    /** Заголовок предмета для чата/логов. */
    public String itemName() {
        return sample.getHoverName().getString();
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, id);
        tag.putUUID(TAG_SELLER, sellerUUID);
        tag.put(TAG_ITEM, sample.save(new CompoundTag()));
        tag.putLong(TAG_PRICE, pricePerUnit);
        tag.putInt(TAG_TOTAL, totalQuantity);
        tag.putInt(TAG_REMAINING, remainingQuantity);
        tag.putLong(TAG_CREATED, createdAt);
        return tag;
    }

    public static SellOrder fromNbt(CompoundTag tag) {
        ItemStack sample = ItemStack.of(tag.getCompound(TAG_ITEM));
        if (sample.isEmpty()) {
            return null;
        }
        return new SellOrder(
                tag.getUUID(TAG_ID),
                tag.getUUID(TAG_SELLER),
                sample,
                tag.getLong(TAG_PRICE),
                tag.getInt(TAG_TOTAL),
                tag.getInt(TAG_REMAINING),
                tag.getLong(TAG_CREATED));
    }
}
package com.valorcraft.exchange.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Заявка на покупку. Средства на полную стоимость заморожены у покупателя
 * (см. {@code ExchangeDataManager.frozenFunds} + нативное эскроу VEconomy).
 */
public record BuyOrder(
        UUID id,
        UUID buyerUUID,
        ItemStack sample,
        long pricePerUnit,
        int totalRequested,
        int fulfilledAmount,
        long createdAt,
        boolean active,
        int refEpoch
) {

    public static final String TAG_ID = "id";
    public static final String TAG_BUYER = "buyer";
    public static final String TAG_ITEM = "item";
    public static final String TAG_PRICE = "price";
    public static final String TAG_TOTAL = "totalQty";
    public static final String TAG_FULFILLED = "fulfilledQty";
    public static final String TAG_CREATED = "createdAt";
    public static final String TAG_ACTIVE = "active";
    public static final String TAG_REF_EPOCH = "refEpoch";

    public static BuyOrder create(UUID buyer, ItemStack sample, long pricePerUnit, int totalRequested) {
        ItemStack copy = sample.copy();
        copy.setCount(1);
        return new BuyOrder(UUID.randomUUID(), buyer, copy, pricePerUnit, totalRequested, 0,
                System.currentTimeMillis(), true, 0);
    }

    /** Заголовок предмета для чата/логов. */
    public String itemName() {
        return sample.getHoverName().getString();
    }

    /** Сколько ещё нужно продать заявке. */
    public int remaining() {
        return Math.max(0, totalRequested - fulfilledAmount);
    }

    /** Заявка выполнена полностью. */
    public boolean isFulfilled() {
        return remaining() <= 0;
    }

    public BuyOrder withProgress(int fulfilled, boolean active, int refEpoch) {
        return new BuyOrder(id, buyerUUID, sample, pricePerUnit, totalRequested, fulfilled,
                createdAt, active, refEpoch);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, id);
        tag.putUUID(TAG_BUYER, buyerUUID);
        tag.put(TAG_ITEM, sample.save(new CompoundTag()));
        tag.putLong(TAG_PRICE, pricePerUnit);
        tag.putInt(TAG_TOTAL, totalRequested);
        tag.putInt(TAG_FULFILLED, fulfilledAmount);
        tag.putLong(TAG_CREATED, createdAt);
        tag.putBoolean(TAG_ACTIVE, active);
        tag.putInt(TAG_REF_EPOCH, refEpoch);
        return tag;
    }

    public static BuyOrder fromNbt(CompoundTag tag) {
        ItemStack sample = ItemStack.of(tag.getCompound(TAG_ITEM));
        if (sample.isEmpty()) {
            return null;
        }
        return new BuyOrder(
                tag.getUUID(TAG_ID),
                tag.getUUID(TAG_BUYER),
                sample,
                tag.getLong(TAG_PRICE),
                tag.getInt(TAG_TOTAL),
                tag.getInt(TAG_FULFILLED),
                tag.getLong(TAG_CREATED),
                tag.getBoolean(TAG_ACTIVE),
                tag.getInt(TAG_REF_EPOCH));
    }
}
package com.valorcraft.exchange.data;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Запись истории транзакции биржи.
 */
public record ExchangeLogEntry(
        long timestamp,
        ExchangeTransactionType type,
        UUID sellerUuid,
        UUID buyerUuid,
        String itemName,
        int quantity,
        long totalPrice
) {

    private static final String K_TS = "ts";
    private static final String K_TYPE = "type";
    private static final String K_SELLER = "seller";
    private static final String K_BUYER = "buyer";
    private static final String K_ITEM = "item";
    private static final String K_QTY = "qty";
    private static final String K_TOTAL = "total";

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_TS, timestamp);
        tag.putString(K_TYPE, type.name());
        if (sellerUuid != null) {
            tag.putUUID(K_SELLER, sellerUuid);
        }
        if (buyerUuid != null) {
            tag.putUUID(K_BUYER, buyerUuid);
        }
        tag.putString(K_ITEM, itemName == null ? "" : itemName);
        tag.putInt(K_QTY, quantity);
        tag.putLong(K_TOTAL, totalPrice);
        return tag;
    }

    public static ExchangeLogEntry fromNbt(CompoundTag tag) {
        try {
            return new ExchangeLogEntry(
                    tag.getLong(K_TS),
                    ExchangeTransactionType.valueOf(tag.getString(K_TYPE)),
                    tag.hasUUID(K_SELLER) ? tag.getUUID(K_SELLER) : null,
                    tag.hasUUID(K_BUYER) ? tag.getUUID(K_BUYER) : null,
                    tag.getString(K_ITEM),
                    tag.getInt(K_QTY),
                    tag.getLong(K_TOTAL));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
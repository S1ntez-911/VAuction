package com.valorcraft.vauction.item;

import java.util.Arrays;
import java.util.Objects;

/**
 * Иммутабельный снимок предмета. Никогда не хранит живой {@code ItemStack}:
 * {@code serializedData} — это сжатый blob штатной NBT-сериализации Forge.
 * Массив копируется на входе и на выходе — snapshot нельзя изменить извне.
 */
public record ItemSnapshot(
        byte[] serializedData,
        String codecVersion,
        String hash,
        String registryId,
        String displayName,
        String searchName,
        int quantity
) {

    public ItemSnapshot {
        Objects.requireNonNull(serializedData, "serializedData");
        Objects.requireNonNull(codecVersion, "codecVersion");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(registryId, "registryId");
        serializedData = serializedData.clone();
        if (serializedData.length == 0) {
            throw new IllegalArgumentException("serializedData must not be empty");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0, got " + quantity);
        }
    }

    @Override
    public byte[] serializedData() {
        return serializedData.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemSnapshot that)) return false;
        return quantity == that.quantity
                && Arrays.equals(serializedData, that.serializedData)
                && Objects.equals(codecVersion, that.codecVersion)
                && Objects.equals(hash, that.hash)
                && Objects.equals(registryId, that.registryId)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(searchName, that.searchName);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(codecVersion, hash, registryId, displayName, searchName, quantity);
        result = 31 * result + Arrays.hashCode(serializedData);
        return result;
    }

    /** Короткий лог-превью (registryId, колво, hash, размеры) — без NBT-дампа. */
    public String toLogSummary() {
        return "ItemSnapshot{registryId=" + registryId
                + ", quantity=" + quantity
                + ", hash=" + hash
                + ", compressedBytes=" + serializedData.length
                + ", codec=" + codecVersion + '}';
    }
}
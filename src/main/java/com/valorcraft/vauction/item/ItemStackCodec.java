package com.valorcraft.vauction.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.Locale;

/**
 * Сериализация полного {@link ItemStack} Forge 1.20.1 в bytes и обратно.
 * <p>
 * Формат хранения:
 * <ul>
 *   <li>как источник истины — штатный {@code ItemStack.serializeNBT()} (CompoundTag
 *       со всеми штатно сериализуемыми данными: id, count и вся внутренняя NBT —
 *       damage, custom name, enchantments, содержимое BlockEntity-контейнеров и пр.);</li>
 *   <li>{@code serializedData} — сжатый исходный CompoundTag ({@link NbtBytes});</li>
 *   <li>рядом хранится {@code codecVersion} = {@value #CODEC_VERSION} и SHA-256
 *       fingerprint несжатых байт (см. {@link ItemFingerprint}).</li>
 * </ul>
 * <p>
 * Правила:
 * <ul>
 *   <li>неизвестная {@code codecVersion} → {@link ItemCodecError#UNSUPPORTED_CODEC} (никогда не «молча»);</li>
 *   <li>превышение {@code maxCompressedItemBytes}/{@code maxUncompressedItemBytes} → {@link ItemCodecError#ITEM_TOO_LARGE};</li>
 *   <li>повреждённый blob / несовпадение hash / id не восстанавливается → {@link ItemCodecError#CORRUPTED_ITEM_DATA};</li>
 *   <li>пустой {@code ItemStack} → {@link ItemCodecError#EMPTY_ITEM}.</li>
 * </ul>
 * <p>
 * Полнота данных: у vanilla- и большинства модовых предметов вся
 * «персональная» информация живёт в item-теге и переживает round-trip целиком.
 * Forge item-capabilities НЕ сериализуются штатной механикой (Minecraft сам их
 * не хранит) — это ограничение фиксировано и задокументировано (см. отчёт).
 */
public final class ItemStackCodec {

    /** Версия формата хранения. Смена формата = новая версия + миграция blob. */
    public static final String CODEC_VERSION = "forge_itemstack_nbt_v1";

    private final int maxCompressedItemBytes;
    private final int maxUncompressedItemBytes;

    public ItemStackCodec(int maxCompressedItemBytes, int maxUncompressedItemBytes) {
        if (maxCompressedItemBytes <= 0 || maxUncompressedItemBytes <= 0) {
            throw new IllegalArgumentException("size limits must be > 0");
        }
        this.maxCompressedItemBytes = maxCompressedItemBytes;
        this.maxUncompressedItemBytes = maxUncompressedItemBytes;
    }

    /** Захватить полный снимок предмета (проверки политики выполняет вызывающий слой). */
    public ItemSnapshot encode(ItemStack stack) throws ItemCodecException {
        if (stack == null || stack.isEmpty()) {
            throw new ItemCodecException(ItemCodecError.EMPTY_ITEM, "empty ItemStack cannot be encoded");
        }
        try {
            CompoundTag root = stack.serializeNBT();

            byte[] raw = NbtBytes.serialize(root);
            if (raw.length > maxUncompressedItemBytes) {
                throw tooLarge("uncompressed", raw.length, maxUncompressedItemBytes);
            }

            byte[] compressed = NbtBytes.gzip(raw);
            if (compressed.length > maxCompressedItemBytes) {
                throw tooLarge("compressed", compressed.length, maxCompressedItemBytes);
            }

            String registryId = root.getString("id");
            if (registryId.isEmpty() || "minecraft:air".equals(registryId)) {
                registryId = stack.getItem().getDescriptionId();
            }

            String displayName = stack.getHoverName().getString();
            String searchName = normalizeSearchName(displayName, registryId);

            return new ItemSnapshot(compressed, CODEC_VERSION,
                    ItemFingerprint.sha256Hex(raw), registryId, displayName, searchName,
                    stack.getCount());
        } catch (IOException e) {
            throw new ItemCodecException(ItemCodecError.INTERNAL_ERROR, "failed to serialize ItemStack", e);
        }
    }

    /**
     * Восстановить ItemStack из снимка с полной проверкой целостности.
     * Возвращает НОВЫЙ стек (модификация не влияет на снимок).
     */
    public ItemStack decode(ItemSnapshot snapshot) throws ItemCodecException {
        if (snapshot == null) {
            throw new ItemCodecException(ItemCodecError.CORRUPTED_ITEM_DATA, "snapshot is null");
        }
        if (!CODEC_VERSION.equals(snapshot.codecVersion())) {
            throw new ItemCodecException(ItemCodecError.UNSUPPORTED_CODEC,
                    "unknown codecVersion '" + snapshot.codecVersion() + "' (supported: " + CODEC_VERSION + ")");
        }
        byte[] compressed = snapshot.serializedData();
        if (compressed.length > maxCompressedItemBytes) {
            throw tooLarge("compressed", compressed.length, maxCompressedItemBytes);
        }

        byte[] raw;
        try {
            raw = NbtBytes.gunzip(compressed, maxUncompressedItemBytes);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("exceeds limit")) {
                throw new ItemCodecException(ItemCodecError.ITEM_TOO_LARGE,
                        "stored item exceeds maxUncompressedItemBytes=" + maxUncompressedItemBytes, e);
            }
            throw new ItemCodecException(ItemCodecError.CORRUPTED_ITEM_DATA,
                    "blob is not valid gzip", e);
        }

        try {
            CompoundTag tag = NbtBytes.deserialize(raw);
            ItemStack stack = ItemStack.of(tag);
            if (stack.isEmpty()) {
                throw new ItemCodecException(ItemCodecError.CORRUPTED_ITEM_DATA,
                        "blob decoded to an empty ItemStack (id missing or unknown)");
            }
            if (!ItemFingerprint.matches(snapshot.hash(), raw)) {
                throw new ItemCodecException(ItemCodecError.CORRUPTED_ITEM_DATA,
                        "item hash mismatch: stored " + snapshot.hash() + " vs calculated "
                        + ItemFingerprint.sha256Hex(raw));
            }
            if (stack.getCount() != snapshot.quantity()) {
                throw new ItemCodecException(ItemCodecError.CORRUPTED_ITEM_DATA,
                        "item quantity changed: stored " + snapshot.quantity() + " vs decoded " + stack.getCount());
            }
            return stack;
        } catch (ItemCodecException e) {
            throw e;
        } catch (IOException e) {
            throw new ItemCodecException(ItemCodecError.CORRUPTED_ITEM_DATA, "blob is not valid NBT", e);
        } catch (Throwable e) {
            throw new ItemCodecException(ItemCodecError.CORRUPTED_ITEM_DATA,
                    "unexpected failure while decoding ItemStack", e);
        }
    }

    /* --------------------------------- internals -------------------------------- */

    private static ItemCodecException tooLarge(String what, int actual, int limit) {
        return new ItemCodecException(ItemCodecError.ITEM_TOO_LARGE,
                "item too large (" + what + ": " + actual + " bytes, limit " + limit + ")");
    }

    /** Поисковая строка: имя в нижнем регистре + id. Никогда null. */
    public static String normalizeSearchName(String displayName, String registryId) {
        String normalized = displayName == null ? registryId : displayName.toLowerCase(Locale.ROOT);
        return registryId == null ? normalized : normalized.trim() + " | " + registryId;
    }
}
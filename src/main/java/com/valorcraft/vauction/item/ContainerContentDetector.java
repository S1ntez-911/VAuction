package com.valorcraft.vauction.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Обнаружение «предметов с содержимым» через штатные NBT-механизмы:
 * <ul>
 *   <li>контейнеры-блоки (shulker box, chest и т.п.): внутри {@code BlockEntityTag}
 *       непустой список {@code Items}, либо {@code LootTable}/{@code LootTableSeed}
 *       (незаполненный лут);</li>
 *   <li>Bundle и предметы с содержимым на top-level: непустой список {@code Items}.</li>
 * </ul>
 * <p>
 * Механизм расширяемый: {@link #addTopLevelKey(String)} / {@link #addBlockKey(String)}
 * позволяют модам регистрировать свои ключи. Если содержимое модового контейнера
 * надёжно определить невозможно — предмет НЕ блокируется (лучше не блокировать,
 * чем рисковать данными). Ограничение задокументировано.
 * <p>
 * Известное ограничение: Forge item-data в теге ItemStack штатно не хранится,
 * поэтому capability-контейнеры данным методом не обнаруживаются.
 */
public final class ContainerContentDetector {

    private static final Set<String> KEY_MARKERS = new LinkedHashSet<>();
    private static final String KEY_BLOCK_ENTITY = "BlockEntityTag";

    static {
        addTopLevelKey("Items");   // Bundle
        addBlockKey("Items");      // shulker / любой BlockItem с начинкой
        addBlockKey("LootTable");
        addBlockKey("LootTableSeed");
    }

    private ContainerContentDetector() {}

    /** Зарегистрировать top-level ключ, указывающий на наличие содержимого. */
    public static void addTopLevelKey(String key) {
        KEY_MARKERS.add("top:" + key);
    }

    /** Зарегистрировать ключ внутри {@code BlockEntityTag}. */
    public static void addBlockKey(String key) {
        KEY_MARKERS.add(KEY_BLOCK_ENTITY + ":" + key);
    }

    /**
     * Проверка: содержит ли ItemStack другие предметы (по известным NBT-маркерам).
     * Пустой стек / отсутствие тега → false.
     */
    public static boolean containsItemContents(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return false;
        }
        CompoundTag root = stack.getTag();
        if (root == null) {
            return false;
        }
        for (String marker : KEY_MARKERS) {
            if (marker.startsWith(KEY_BLOCK_ENTITY + ":")) {
                String key = marker.substring(KEY_BLOCK_ENTITY.length() + 1);
                if (root.contains(KEY_BLOCK_ENTITY, CompoundTag.TAG_COMPOUND)
                        && (isNonEmptyList(root.getCompound(KEY_BLOCK_ENTITY), key)
                        || rawStringPresent(root.getCompound(KEY_BLOCK_ENTITY), key))) {
                    return true;
                }
            } else if (marker.startsWith("top:")) {
                String key = marker.substring(4);
                if (isNonEmptyList(root, key) || rawStringPresent(root, key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNonEmptyList(CompoundTag tag, String key) {
        return tag.contains(key, CompoundTag.TAG_LIST) && tag.getList(key, CompoundTag.TAG_COMPOUND).size() > 0;
    }

    private static boolean rawStringPresent(CompoundTag tag, String key) {
        return tag.contains(key, CompoundTag.TAG_STRING);
    }
}
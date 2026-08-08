package com.valorcraft.vauction.item;

/**
 * Типовые ошибки сериализации/политики предметов — все должны быть явно диагностируемыми.
 */
public enum ItemCodecError {
    /** Пустой ItemStack (EMPTY/null). */
    EMPTY_ITEM,
    /** Превышен лимит размера (сжатого или несжатого NBT). */
    ITEM_TOO_LARGE,
    /** Blob повреждён: битые gzip/NBT, хеш не совпал, количество не совпало. */
    CORRUPTED_ITEM_DATA,
    /** Неизвестная версия формата codec'а. */
    UNSUPPORTED_CODEC,
    /** Внутренняя ошибка (не ожидаемая в нормальном потоке). */
    INTERNAL_ERROR
}
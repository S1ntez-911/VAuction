package com.valorcraft.vauction.item;

/**
 * Контролируемая ошибка предметного слоя. Всегда несёт {@link ItemCodecError}
 * и диагностическое сообщение (без гигантского NBT в логе).
 */
public class ItemCodecException extends Exception {

    private final ItemCodecError error;

    public ItemCodecException(ItemCodecError error, String message) {
        super(message);
        this.error = error;
    }

    public ItemCodecException(ItemCodecError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public ItemCodecError error() {
        return error;
    }
}
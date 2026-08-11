package com.valorcraft.vauction.gui;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Converts player-facing decimal currency text to VEconomy minor units. */
final class CurrencyInput {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:[.,][0-9]+)?");

    private CurrencyInput() {}

    static long parse(String text) throws InvalidPrice {
        final int scale;
        try {
            scale = CurrencyText.decimalPlaces();
        } catch (IllegalStateException e) {
            throw new InvalidPrice("Не удалось получить настройки валюты VEconomy.");
        }
        return parse(text, scale);
    }

    static long parse(String text, int scale) throws InvalidPrice {
        if (scale < 0 || scale > 18) {
            throw new IllegalArgumentException("scale must be between 0 and 18");
        }
        if (text == null || text.isBlank() || !DECIMAL.matcher(text).matches()) {
            throw new InvalidPrice("Некорректная цена. Пример: " + example(scale));
        }

        int separator = Math.max(text.indexOf('.'), text.indexOf(','));
        int fractionDigits = separator < 0 ? 0 : text.length() - separator - 1;
        if (fractionDigits > scale) {
            if (scale == 0) {
                throw new InvalidPrice("Цена должна быть целым числом.");
            }
            throw new InvalidPrice("Цена может содержать не более " + scale + " знаков после запятой.");
        }

        try {
            BigDecimal major = new BigDecimal(text.replace(',', '.'));
            long minor = major.movePointRight(scale).longValueExact();
            if (minor <= 0) {
                throw new InvalidPrice("Цена должна быть больше нуля.");
            }
            return minor;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new InvalidPrice("Цена слишком большая.");
        }
    }

    static String formatAmount(long minor, int scale) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }

    static String formatAmount(long minor) {
        return formatAmount(minor, CurrencyText.decimalPlaces());
    }

    static String example(int scale) {
        return scale == 0 ? "1" : "1." + "0".repeat(scale);
    }

    static String example() {
        try {
            return example(CurrencyText.decimalPlaces());
        } catch (IllegalStateException e) {
            return "1";
        }
    }

    static final class InvalidPrice extends Exception {
        InvalidPrice(String message) {
            super(message);
        }
    }
}

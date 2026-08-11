package com.valorcraft.vauction.gui;

import net.minecraft.network.chat.TextColor;

import java.util.LinkedHashMap;
import java.util.Map;

/** ValorCraft web palette adapted to vanilla Minecraft text components. */
final class MarketPalette {
    /** Значения по умолчанию: используются как опора и для дефолтного конфига. */
    static final Map<String, String> DEFAULT_COLORS = Map.ofEntries(
            Map.entry("brand", "D4A84A"),
            Map.entry("text", "E8E6DA"),
            Map.entry("muted", "9A9A9A"),
            Map.entry("success", "63D471"),
            Map.entry("sell", "FFB454"),
            Map.entry("warning", "FFD35B"),
            Map.entry("error", "FF6B6B"),
            Map.entry("info", "6CB4EE"),
            Map.entry("separator", "5A5A5A"));

    private static volatile TextColor brand = TextColor.fromRgb(0xD4A84A);
    private static volatile TextColor text = TextColor.fromRgb(0xE8E6DA);
    private static volatile TextColor muted = TextColor.fromRgb(0x9A9A9A);
    private static volatile TextColor success = TextColor.fromRgb(0x63D471);
    private static volatile TextColor sell = TextColor.fromRgb(0xFFB454);
    private static volatile TextColor warning = TextColor.fromRgb(0xFFD35B);
    private static volatile TextColor error = TextColor.fromRgb(0xFF6B6B);
    private static volatile TextColor info = TextColor.fromRgb(0x6CB4EE);
    /** Dark grey divider between the exchange block and the native item tooltip. */
    private static volatile TextColor separator = TextColor.fromRgb(0x5A5A5A);

    private MarketPalette() {}

    static TextColor byKey(String key) {
        return switch (key == null ? "" : key) {
            case "brand" -> brand;
            case "muted" -> muted;
            case "success" -> success;
            case "sell" -> sell;
            case "warning" -> warning;
            case "error" -> error;
            case "info" -> info;
            case "separator" -> separator;
            default -> text;
        };
    }

    /** Применяет секцию colors конфига (может быть null). Пропущенные цвета остаются прежними. */
    static void apply(Map<String, String> colors) {
        if (colors == null) return;
        String v = colors.get("brand");
        if (v != null) brand = parse(v);
        v = colors.get("text");
        if (v != null) text = parse(v);
        v = colors.get("muted");
        if (v != null) muted = parse(v);
        v = colors.get("success");
        if (v != null) success = parse(v);
        v = colors.get("sell");
        if (v != null) sell = parse(v);
        v = colors.get("warning");
        if (v != null) warning = parse(v);
        v = colors.get("error");
        if (v != null) error = parse(v);
        v = colors.get("info");
        if (v != null) info = parse(v);
        v = colors.get("separator");
        if (v != null) separator = parse(v);
    }

    private static TextColor parse(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return TextColor.fromRgb((int) Long.parseLong(h, 16));
        } catch (RuntimeException e) {
            return TextColor.fromRgb(0xE8E6DA);
        }
    }
}
package com.valorcraft.vauction.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

final class MarketText {
    private MarketText() {}

    static Component brand() {
        return colored("◆ Биржа ValorCraft", MarketPalette.BRAND);
    }

    static Component text(String text) {
        return colored(text, MarketPalette.TEXT);
    }

    static Component muted(String text) {
        return colored(text, MarketPalette.MUTED);
    }

    static Component action(String text, TextColor color) {
        return colored(text, color).copy().withStyle(style -> style.withBold(true));
    }

    static Component labelValue(String label, String value, TextColor valueColor) {
        MutableComponent line = Component.literal(label + ": ")
                .withStyle(style -> style.withColor(MarketPalette.MUTED));
        return line.append(colored(value, valueColor));
    }

    static Component colored(String text, TextColor color) {
        return Component.literal(text).withStyle(style -> style.withColor(color));
    }
}

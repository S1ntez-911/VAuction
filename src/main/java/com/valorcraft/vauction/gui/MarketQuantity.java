package com.valorcraft.vauction.gui;

final class MarketQuantity {
    private MarketQuantity() {}

    static int buyPreset(int preset) {
        return Math.max(1, preset);
    }

    static int sellPreset(int preset, int available) {
        return Math.max(1, Math.min(Math.max(0, available), Math.max(1, preset)));
    }

    static int sellAll(int available) {
        return Math.max(1, available);
    }
}

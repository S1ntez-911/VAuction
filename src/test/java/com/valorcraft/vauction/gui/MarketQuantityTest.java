package com.valorcraft.vauction.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketQuantityTest {
    @Test
    void sellPresetsClampToCurrentAvailability() {
        assertEquals(1, MarketQuantity.sellPreset(1, 347));
        assertEquals(16, MarketQuantity.sellPreset(16, 347));
        assertEquals(64, MarketQuantity.sellPreset(64, 347));
        assertEquals(42, MarketQuantity.sellPreset(64, 42));
    }

    @Test
    void sellAllUsesFreshAvailabilityEveryTime() {
        assertEquals(347, MarketQuantity.sellAll(347));
        assertEquals(200, MarketQuantity.sellAll(200));
    }

    @Test
    void buyPresetsAreExact() {
        assertEquals(1, MarketQuantity.buyPreset(1));
        assertEquals(16, MarketQuantity.buyPreset(16));
        assertEquals(32, MarketQuantity.buyPreset(32));
        assertEquals(64, MarketQuantity.buyPreset(64));
    }
}

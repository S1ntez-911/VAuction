package com.valorcraft.vauction.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyInputTest {
    @Test
    void parsesHumanReadablePricesToMinorUnitsExactly() throws Exception {
        assertEquals(100, CurrencyInput.parse("1", 2));
        assertEquals(140, CurrencyInput.parse("1.4", 2));
        assertEquals(140, CurrencyInput.parse("1.40", 2));
        assertEquals(1, CurrencyInput.parse("0.01", 2));
        assertEquals(140, CurrencyInput.parse("1,4", 2));
    }

    @Test
    void acceptsLargestRepresentableMinorValue() throws Exception {
        assertEquals(Long.MAX_VALUE, CurrencyInput.parse("92233720368547758.07", 2));
    }

    @Test
    void rejectsInvalidNonPositiveAndScientificPrices() {
        assertThrows(CurrencyInput.InvalidPrice.class, () -> CurrencyInput.parse("0", 2));
        assertThrows(CurrencyInput.InvalidPrice.class, () -> CurrencyInput.parse("-1", 2));
        assertThrows(CurrencyInput.InvalidPrice.class, () -> CurrencyInput.parse("abc", 2));
        assertThrows(CurrencyInput.InvalidPrice.class, () -> CurrencyInput.parse("1e6", 2));
        assertThrows(CurrencyInput.InvalidPrice.class, () -> CurrencyInput.parse("", 2));
    }

    @Test
    void rejectsExcessScaleWithoutRounding() {
        CurrencyInput.InvalidPrice error = assertThrows(CurrencyInput.InvalidPrice.class,
                () -> CurrencyInput.parse("1.234", 2));
        assertTrue(error.getMessage().contains("не более 2 знаков"));
        assertThrows(CurrencyInput.InvalidPrice.class, () -> CurrencyInput.parse("1.1", 0));
    }

    @Test
    void rejectsLongOverflow() {
        assertThrows(CurrencyInput.InvalidPrice.class,
                () -> CurrencyInput.parse("92233720368547758.08", 2));
        assertThrows(CurrencyInput.InvalidPrice.class,
                () -> CurrencyInput.parse("999999999999999999999999999999", 2));
    }

    @Test
    void commandInputRoundTripsThroughTheSameDisplayScale() throws Exception {
        for (long minor : new long[]{1, 140, 1_575, Long.MAX_VALUE}) {
            String displayedAmount = CurrencyInput.formatAmount(minor, 2);
            assertEquals(minor, CurrencyInput.parse(displayedAmount, 2));
        }
    }
}

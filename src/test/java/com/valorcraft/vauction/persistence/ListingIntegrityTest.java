package com.valorcraft.vauction.persistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ListingIntegrityTest {
    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void acceptsValidActiveAndPendingListings() {
        assertDoesNotThrow(() -> validate(false, 100, "ACTIVE", null, null));
        assertDoesNotThrow(() -> validate(false, 100, "PENDING_PAYMENT", BUYER, "vauction:test"));
    }

    @Test
    void rejectsUnsafeLegacyRows() {
        assertThrows(IllegalArgumentException.class, () -> validate(true, 100, "ACTIVE", null, null));
        assertThrows(IllegalArgumentException.class, () -> validate(false, 0, "ACTIVE", null, null));
        assertThrows(IllegalArgumentException.class, () -> validate(false, 100, "PENDING_PAYMENT", null, null));
        assertThrows(IllegalArgumentException.class, () -> validate(false, 100, "SOLD", null, "vauction:test"));
    }

    private static void validate(boolean empty, long price, String state, UUID buyer, String escrow) {
        ListingIntegrity.validateFields(empty, price, "Seller", 1_000L, 2_000L, state, buyer, escrow);
    }
}

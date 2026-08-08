package com.valorcraft.vauction.domain;

import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.domain.listing.AuctionListing;
import com.valorcraft.vauction.domain.listing.ListingStatus;
import com.valorcraft.vauction.domain.sale.AuctionSale;
import com.valorcraft.vauction.item.ItemSnapshot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Чистые доменные тесты: инварианты и переходы состояний (без БД и Minecraft). */
class DomainTest {

    private static ItemSnapshot item() {
        return new ItemSnapshot(new byte[] {1, 2, 3}, "forge_itemstack_nbt_v1",
                "hashhashhashhashhashhashhashhash", "minecraft:diamond",
                "Diamond", "diamond", 1);
    }

    /* ------------------------------ listing ------------------------------ */

    @Test
    void listingLifecycleActiveReservedSold() {
        long now = 1_000_000L;
        AuctionListing listing = AuctionListing.newListing(UUID.randomUUID(), item(), 1000)
                .fee(1).commissionBps(500).times(now, now + 100_000L).build();
        assertEquals(ListingStatus.ACTIVE, listing.status());
        assertEquals(0, listing.version());

        UUID buyer = UUID.randomUUID();
        AuctionListing reserved = listing.toReserved(buyer, "res-1", now + 500, now + 60_000, now + 20);
        assertEquals(ListingStatus.RESERVED, reserved.status());
        assertEquals(buyer, reserved.buyerUuid());
        assertEquals(0, reserved.version(), "переходы не должны менять версию домена");

        AuctionListing sold = reserved.toSold(buyer, now + 30);
        assertEquals(ListingStatus.SOLD, sold.status());
        assertTrue(sold.isTerminal());
    }

    @Test
    void listingRejectsInvalidTransitionsAndInvariants() {
        long now = 1_000L;
        AuctionListing listing = AuctionListing.newListing(UUID.randomUUID(), item(), 1000)
                .times(now, now + 100_000L).build();

        assertThrows(IllegalStateException.class, () -> listing.toSold(UUID.randomUUID(), now),
                "только RESERVED может стать SOLD");
        AuctionListing reserved = listing.toReserved(UUID.randomUUID(), "r", now, now + 1, now);
        assertThrows(IllegalStateException.class, () -> reserved.toReserved(UUID.randomUUID(),
                "r2", now, now + 1, now), "повторная резервация должна быть запрещена");

        AuctionListing cancelled = listing.toCancelled("отмена", null, now + 10);
        assertThrows(IllegalStateException.class, () -> cancelled.toCancelled("again", null, now + 11),
                "CANCELLED дважды не допускается");

        assertThrows(IllegalArgumentException.class, () -> AuctionListing.newListing(
                UUID.randomUUID(), item(), 0).times(now, now + 1).build(), "priceMinor должен быть > 0");
        assertThrows(IllegalArgumentException.class, () -> AuctionListing.newListing(
                UUID.randomUUID(), item(), 10).times(now + 5, now).build(), "expires должен быть после created");
    }

    /* ------------------------- delivery ------------------------- */

    @Test
    void deliveryLifecycleHappyPath() {
        long now = 1_000L;
        AuctionDelivery delivery = AuctionDelivery.newDelivery(UUID.randomUUID(), 1L, "op-1",
                DeliveryType.PURCHASED, item(), now).dedupeKey("sale:1").build();
        assertEquals(DeliveryState.PENDING, delivery.state());

        AuctionDelivery claimable = delivery.toClaimable(now + 3_600_000L, "token-1");
        assertEquals(DeliveryState.CLAIMABLE, claimable.state());

        AuctionDelivery claiming = claimable.toClaiming(now + 10);
        assertEquals(DeliveryState.CLAIMING, claiming.state());

        AuctionDelivery claimed = claiming.toClaimed(now + 11);
        assertEquals(DeliveryState.CLAIMED, claimed.state());
    }

    @Test
    void deliveryRejectsWrongOrder() {
        long now = 1_000L;
        AuctionDelivery delivery = AuctionDelivery.newDelivery(UUID.randomUUID(), 1L, "op-1",
                DeliveryType.CANCELLED_RETURN, item(), now).dedupeKey("return:1").build();
        assertThrows(IllegalStateException.class, () -> delivery.toClaiming(now),
                "PENDING не может стать CLAIMING");
        assertThrows(IllegalStateException.class, () -> delivery.toClaimable(now, "t")
                .toClaimable(now + 1, "t2"), "CLAIMABLE не может быть переустановлен");
        assertThrows(IllegalStateException.class, () -> delivery.toClaimed(now + 1),
                "PENDING не может стать CLAIMED");
    }

    /* ------------------------- sale ------------------------- */

    @Test
    void saleInvariantEnforced() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        long now = System.currentTimeMillis();

        AuctionSale ok = AuctionSale.newSale(seller, buyer, 500, "esc-1", "hash-1", now)
                .purchaseOperationId("op-1").listingId(9L).commissionMinor(50).sellerNetMinor(450).build();
        assertEquals(500, ok.grossMinor());

        assertThrows(IllegalArgumentException.class,
                () -> AuctionSale.newSale(seller, buyer, 500, "esc-2", "hash-2", now)
                        .purchaseOperationId("op-2")
                        .commissionMinor(50).sellerNetMinor(400).build(),
                "gross != commission + net должен падать");
        assertThrows(IllegalArgumentException.class,
                () -> AuctionSale.newSale(seller, buyer, 0, "esc-3", "hash-3", now)
                        .purchaseOperationId("op-3").build(),
                "gross должен быть > 0");
    }
}
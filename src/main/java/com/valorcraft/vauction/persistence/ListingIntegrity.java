package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.model.AuctionListing;

import java.util.UUID;

/** Semantic checks also protect databases created by schema versions without SQL CHECK clauses. */
final class ListingIntegrity {
    private ListingIntegrity() {}

    static void validate(AuctionListing listing) {
        validateFields(listing.item().isEmpty(), listing.price(), listing.sellerName(), listing.createdAt(),
                listing.expiresAt(), listing.state().name(), listing.buyerId(), listing.escrowReference());
    }

    static void validateFields(boolean emptyItem, long price, String sellerName, long createdAt,
                               long expiresAt, String state, UUID buyerId, String escrowReference) {
        if (emptyItem) throw new IllegalArgumentException("пустой предмет");
        if (price <= 0L) throw new IllegalArgumentException("неположительная цена");
        if (sellerName == null || sellerName.isBlank())
            throw new IllegalArgumentException("пустое имя продавца");
        if (createdAt <= 0L || expiresAt <= createdAt)
            throw new IllegalArgumentException("некорректные временные метки");
        if ("PENDING_PAYMENT".equals(state)
                && (buyerId == null || escrowReference == null || escrowReference.isBlank()))
            throw new IllegalArgumentException("PENDING без покупателя или escrow");
        if ("SOLD".equals(state) && buyerId == null)
            throw new IllegalArgumentException("SOLD без покупателя");
    }
}

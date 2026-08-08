package com.valorcraft.vauction.domain.delivery;

import com.valorcraft.vauction.item.ItemSnapshot;

import java.util.Objects;
import java.util.UUID;

/**
 * Домен-сущность «выдача предмета игроку». Иммутабельна.
 * Фактическое добавление предмета в инвентарь на этом этапе не реализовано —
 * модель и persistence готовы.
 */
public record AuctionDelivery(
        long deliveryId,
        String dedupeKey,
        UUID playerUuid,
        long listingId,
        String operationId,
        DeliveryType deliveryType,
        DeliveryState state,
        ItemSnapshot item,
        long createdAt,
        Long claimableAt,
        Long claimStartedAt,
        Long claimedAt,
        String claimToken,
        String lastError,
        int version
) {

    public AuctionDelivery {
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(deliveryType, "deliveryType");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(item, "item");
        if (dedupeKey.isBlank() || dedupeKey.length() > 191) {
            throw new IllegalArgumentException("dedupeKey must be 1..191 chars");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }

    /* ------------------------------- transitions ------------------------------- */

    public AuctionDelivery toClaimable(long claimableAt, String claimToken) {
        if (state != DeliveryState.PENDING) {
            throw new IllegalStateException("only PENDING can become CLAIMABLE, got " + state);
        }
        return new AuctionDelivery(deliveryId, dedupeKey, playerUuid, listingId, operationId,
                deliveryType, DeliveryState.CLAIMABLE, item, createdAt, claimableAt,
                claimStartedAt, claimedAt, claimToken, lastError, version);
    }

    public AuctionDelivery toClaiming(long claimStartedAt) {
        if (state != DeliveryState.CLAIMABLE) {
            throw new IllegalStateException("only CLAIMABLE can become CLAIMING, got " + state);
        }
        return new AuctionDelivery(deliveryId, dedupeKey, playerUuid, listingId, operationId,
                deliveryType, DeliveryState.CLAIMING, item, createdAt, claimableAt,
                claimStartedAt, claimedAt, claimToken, lastError, version);
    }

    public AuctionDelivery toClaimed(long claimedAt) {
        if (state != DeliveryState.CLAIMING && state != DeliveryState.CLAIMABLE) {
            throw new IllegalStateException("cannot claim delivery in state " + state);
        }
        return new AuctionDelivery(deliveryId, dedupeKey, playerUuid, listingId, operationId,
                deliveryType, DeliveryState.CLAIMED, item, createdAt, claimableAt,
                claimStartedAt, claimedAt, claimToken, lastError, version);
    }

    public AuctionDelivery toFailed(String error) {
        if (state == DeliveryState.CLAIMED) {
            throw new IllegalStateException("CLAIMED delivery cannot fail");
        }
        return new AuctionDelivery(deliveryId, dedupeKey, playerUuid, listingId, operationId,
                deliveryType, DeliveryState.FAILED, item, createdAt, claimableAt,
                claimStartedAt, claimedAt, claimToken, error, version);
    }

    public static Builder newDelivery(UUID playerUuid, long listingId, String operationId,
                                      DeliveryType type, ItemSnapshot item, long createdAt) {
        return new Builder(playerUuid, listingId, operationId, type, item, createdAt);
    }

    public static final class Builder {
        private final UUID playerUuid;
        private final long listingId;
        private final String operationId;
        private final DeliveryType deliveryType;
        private final ItemSnapshot item;
        private final long createdAt;
        private String dedupeKey;
        private DeliveryState state = DeliveryState.PENDING;

        private Builder(UUID playerUuid, long listingId, String operationId, DeliveryType deliveryType,
                        ItemSnapshot item, long createdAt) {
            this.playerUuid = playerUuid;
            this.listingId = listingId;
            this.operationId = operationId;
            this.deliveryType = deliveryType;
            this.item = item;
            this.createdAt = createdAt;
        }

        public Builder dedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; return this; }

        public Builder state(DeliveryState state) { this.state = state; return this; }

        public AuctionDelivery build() {
            return new AuctionDelivery(0L, dedupeKey, playerUuid, listingId, operationId,
                    deliveryType, state, item, createdAt, null, null, null, null, null, 0);
        }
    }
}
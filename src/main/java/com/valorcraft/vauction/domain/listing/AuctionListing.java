package com.valorcraft.vauction.domain.listing;

import com.valorcraft.vauction.item.ItemSnapshot;

import java.util.Objects;
import java.util.UUID;

/**
 * Домен-сущность лота. Иммутабельна; все переходы создают новый экземпляр старого
 * состояния и сохраняются через optimistic lock ({@code version}).
 * <p>
 * Не содержит ни SQL, ни Forge-классов (только {@link ItemSnapshot}).
 * Деньги — всегда в минимальных единицах (long), никаких double.
 */
public record AuctionListing(
        long listingId,
        UUID sellerUuid,
        ListingStatus status,
        ItemSnapshot item,
        long priceMinor,
        long listingFeeMinor,
        int commissionBps,
        long createdAt,
        long expiresAt,
        long updatedAt,
        UUID buyerUuid,
        String reservationId,
        Long reservedAt,
        Long reservedUntil,
        String cancelReason,
        UUID adminActorUuid,
        int version
) {

    public AuctionListing {
        Objects.requireNonNull(sellerUuid, "sellerUuid");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(item, "item");
        if (priceMinor <= 0) {
            throw new IllegalArgumentException("priceMinor must be > 0, got " + priceMinor);
        }
        if (listingFeeMinor < 0) {
            throw new IllegalArgumentException("listingFeeMinor must be >= 0, got " + listingFeeMinor);
        }
        if (commissionBps < 0) {
            throw new IllegalArgumentException("commissionBps must be >= 0, got " + commissionBps);
        }
        if (expiresAt <= createdAt) {
            throw new IllegalArgumentException("expiresAt must be > createdAt");
        }
        if (updatedAt < createdAt) {
            throw new IllegalArgumentException("updatedAt must be >= createdAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }

    /* ------------------------------- transitions ------------------------------- */

    public AuctionListing toReserved(UUID buyer, String reservationId, long reservedAt, long reservedUntil, long now) {
        if (status != ListingStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE can be reserved, got " + status);
        }
        return new AuctionListing(listingId, sellerUuid, ListingStatus.RESERVED, item,
                priceMinor, listingFeeMinor, commissionBps, createdAt, expiresAt, now,
                Objects.requireNonNull(buyer, "buyer"), Objects.requireNonNull(reservationId, "reservationId"),
                reservedAt, reservedUntil, cancelReason, adminActorUuid, version);
    }

    public AuctionListing toSold(UUID buyer, long now) {
        if (status != ListingStatus.RESERVED) {
            throw new IllegalStateException("only RESERVED can be sold, got " + status);
        }
        return new AuctionListing(listingId, sellerUuid, ListingStatus.SOLD, item,
                priceMinor, listingFeeMinor, commissionBps, createdAt, expiresAt, now,
                Objects.requireNonNull(buyer, "buyer"), reservationId, reservedAt, reservedUntil,
                cancelReason, adminActorUuid, version);
    }

    public AuctionListing toCancelled(String reason, UUID adminActor, long now) {
        if (status == ListingStatus.SOLD || status == ListingStatus.CANCELLED) {
            throw new IllegalStateException("cannot cancel listing in state " + status);
        }
        return new AuctionListing(listingId, sellerUuid, ListingStatus.CANCELLED, item,
                priceMinor, listingFeeMinor, commissionBps, createdAt, expiresAt, now,
                buyerUuid, reservationId, reservedAt, reservedUntil, reason, adminActor, version);
    }

    public AuctionListing toExpired(long now) {
        if (status == ListingStatus.SOLD || status == ListingStatus.CANCELLED) {
            throw new IllegalStateException("cannot expire listing in state " + status);
        }
        return new AuctionListing(listingId, sellerUuid, ListingStatus.EXPIRED, item,
                priceMinor, listingFeeMinor, commissionBps, createdAt, expiresAt, now,
                buyerUuid, reservationId, reservedAt, reservedUntil, cancelReason, adminActorUuid, version);
    }

    public AuctionListing toFailed(long now, String reason) {
        return new AuctionListing(listingId, sellerUuid, ListingStatus.FAILED, item,
                priceMinor, listingFeeMinor, commissionBps, createdAt, expiresAt, now,
                buyerUuid, reservationId, reservedAt, reservedUntil, reason, adminActorUuid, version);
    }

    /** True, если лот ещё торгуется (не завершён). */
    public boolean isTerminal() {
        return switch (status) {
            case SOLD, CANCELLED, EXPIRED, FAILED -> true;
            case ACTIVE, RESERVED -> false;
        };
    }

    /** True, если срок истёк относительно текущего времени. */
    public boolean isExpiredAt(long now) {
        return now >= expiresAt;
    }

    /** Builder-точка для создания нового лота (listingId ещё неизвестен). */
    public static Builder newListing(UUID sellerUuid, ItemSnapshot item, long priceMinor) {
        return new Builder(sellerUuid, item, priceMinor);
    }

    public static final class Builder {
        private final UUID sellerUuid;
        private final ItemSnapshot item;
        private final long priceMinor;
        private long listingFeeMinor;
        private int commissionBps;
        private long createdAt;
        private long expiresAt;
        private long updatedAt;

        private Builder(UUID sellerUuid, ItemSnapshot item, long priceMinor) {
            this.sellerUuid = Objects.requireNonNull(sellerUuid, "sellerUuid");
            this.item = Objects.requireNonNull(item, "item");
            this.priceMinor = priceMinor;
        }

        public Builder fee(long listingFeeMinor) { this.listingFeeMinor = listingFeeMinor; return this; }

        public Builder commissionBps(int bps) { this.commissionBps = bps; return this; }

        public Builder times(long createdAt, long expiresAt) {
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.updatedAt = createdAt;
            return this;
        }

        public AuctionListing build() {
            return new AuctionListing(0L, sellerUuid, ListingStatus.ACTIVE, item, priceMinor,
                    listingFeeMinor, commissionBps, createdAt, expiresAt, updatedAt,
                    null, null, null, null, null, null, 0);
        }
    }
}
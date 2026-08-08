package com.valorcraft.vauction.domain.sale;

import java.util.Objects;
import java.util.UUID;

/**
 * Домен-сущность завершённой продажи. Иммутабельна.
 * <p>
 * Инвариант: gross = commission + net (проверяется в конструкторе И дублируется
 * CHECK-constraint'ом в БД — двойная защита). Никаких double.
 */
public record AuctionSale(
        long saleId,
        long listingId,
        String purchaseOperationId,
        UUID sellerUuid,
        UUID buyerUuid,
        long grossMinor,
        long commissionMinor,
        long sellerNetMinor,
        String escrowReference,
        String itemHash,
        long soldAt
) {

    public AuctionSale {
        Objects.requireNonNull(sellerUuid, "sellerUuid");
        Objects.requireNonNull(buyerUuid, "buyerUuid");
        Objects.requireNonNull(escrowReference, "escrowReference");
        Objects.requireNonNull(itemHash, "itemHash");
        Objects.requireNonNull(purchaseOperationId, "purchaseOperationId");
        if (grossMinor <= 0) {
            throw new IllegalArgumentException("grossMinor must be > 0, got " + grossMinor);
        }
        if (commissionMinor < 0 || sellerNetMinor < 0) {
            throw new IllegalArgumentException("commission/sellerNet must be >= 0");
        }
        if (grossMinor != commissionMinor + sellerNetMinor) {
            throw new IllegalArgumentException(
                    "gross must equal commission + sellerNet: " + grossMinor + " != "
                            + commissionMinor + " + " + sellerNetMinor);
        }
        if (soldAt <= 0) {
            throw new IllegalArgumentException("soldAt must be > 0");
        }
    }

    public static Builder newSale(UUID sellerUuid, UUID buyerUuid, long grossMinor,
                                  String escrowReference, String itemHash, long soldAt) {
        return new Builder(sellerUuid, buyerUuid, grossMinor, escrowReference, itemHash, soldAt);
    }

    public static final class Builder {
        private final UUID sellerUuid;
        private final UUID buyerUuid;
        private final long grossMinor;
        private final String escrowReference;
        private final String itemHash;
        private final long soldAt;
        private String purchaseOperationId;
        private long commissionMinor;
        private long sellerNetMinor;
        private long listingId;

        private Builder(UUID sellerUuid, UUID buyerUuid, long grossMinor, String escrowReference,
                        String itemHash, long soldAt) {
            this.sellerUuid = sellerUuid;
            this.buyerUuid = buyerUuid;
            this.grossMinor = grossMinor;
            this.escrowReference = escrowReference;
            this.itemHash = itemHash;
            this.soldAt = soldAt;
        }

        /** Идемпотентный ключ операции покупки. */
        public Builder purchaseOperationId(String id) { this.purchaseOperationId = id; return this; }

        /** Лот, по которому совершена продажа. */
        public Builder listingId(long listingId) { this.listingId = listingId; return this; }

        /** Комиссия аукциона (минимальные единицы). */
        public Builder commissionMinor(long v) { this.commissionMinor = v; return this; }

        /** Доля продавца net (минимальные единицы). */
        public Builder sellerNetMinor(long v) { this.sellerNetMinor = v; return this; }

        public AuctionSale build() {
            return new AuctionSale(0L, listingId, purchaseOperationId, sellerUuid, buyerUuid,
                    grossMinor, commissionMinor, sellerNetMinor, escrowReference, itemHash, soldAt);
        }
    }
}
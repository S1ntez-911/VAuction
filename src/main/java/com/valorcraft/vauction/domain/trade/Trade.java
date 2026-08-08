package com.valorcraft.vauction.domain.trade;

import com.valorcraft.vauction.domain.order.OrderSide;

import java.util.Objects;
import java.util.UUID;

/**
 * Один исполненный fill (частичный или полный) стакана.
 * В БД каждый fill — отдельная строка {@code auction_trades} (аудит, идемпотентность).
 * <p>
 * Цена сделки — цена maker-ордера (resting order price), см.
 * {@code makerSide} и {@code executionPrice} = {@code maker.pricePerUnit}.
 */
public record Trade(
        UUID tradeId,
        String marketKey,
        UUID buyOrderId,
        UUID sellOrderId,
        OrderSide makerSide,
        long executionPrice,
        int quantity,
        long grossMinor,        // quantity * executionPrice
        long commissionMinor,
        long sellerNetMinor,
        UUID buyerUuid,
        UUID sellerUuid,
        String escrowReference,
        TradeState state,
        long createdAt,
        Long settledAt,
        int version
) {

    public Trade {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(marketKey, "marketKey");
        Objects.requireNonNull(buyOrderId, "buyOrderId");
        Objects.requireNonNull(sellOrderId, "sellOrderId");
        Objects.requireNonNull(makerSide, "makerSide");
        Objects.requireNonNull(buyerUuid, "buyerUuid");
        Objects.requireNonNull(sellerUuid, "sellerUuid");
        Objects.requireNonNull(state, "state");
        if (executionPrice <= 0) {
            throw new IllegalArgumentException("executionPrice must be > 0");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (grossMinor <= 0) {
            throw new IllegalArgumentException("gross must be > 0");
        }
        if (commissionMinor < 0 || grossMinor < commissionMinor) {
            throw new IllegalArgumentException("commission must be in [0, gross]");
        }
        if (grossMinor != Math.multiplyExact(executionPrice, (long) quantity)) {
            throw new IllegalArgumentException("gross must equal executionPrice * quantity");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }

    /** Доля продавца (net) после комиссии. */
    public long sellerNet() {
        return grossMinor - commissionMinor;
    }

    public Trade settled(long now) {
        return new Trade(tradeId, marketKey, buyOrderId, sellOrderId, makerSide,
                executionPrice, quantity, grossMinor, commissionMinor, grossMinor - commissionMinor,
                buyerUuid, sellerUuid, escrowReference, TradeState.SETTLED, createdAt, now, version);
    }

    public Trade failed(String reason, long now) {
        return new Trade(tradeId, marketKey, buyOrderId, sellOrderId, makerSide,
                executionPrice, quantity, grossMinor, commissionMinor, grossMinor - commissionMinor,
                buyerUuid, sellerUuid, escrowReference, TradeState.FAILED, createdAt, null, version);
    }

    public static Builder newTrade(String marketKey, UUID buyOrderId, UUID sellOrderId,
                                   OrderSide makerSide, long executionPrice, int quantity,
                                   long commissionMinor, UUID buyerUuid, UUID sellerUuid,
                                   String escrowReference, long now) {
        return new Builder(marketKey, buyOrderId, sellOrderId, makerSide, executionPrice,
                quantity, commissionMinor, buyerUuid, sellerUuid, escrowReference, now);
    }

    public static final class Builder {
        private final String marketKey;
        private final UUID buyOrderId;
        private final UUID sellOrderId;
        private final OrderSide makerSide;
        private final long executionPrice;
        private final int quantity;
        private final long commissionMinor;
        private final UUID buyerUuid;
        private final UUID sellerUuid;
        private final String escrowReference;
        private final long createdAt;
        private UUID tradeId;

        private Builder(String marketKey, UUID buyOrderId, UUID sellOrderId, OrderSide makerSide,
                        long executionPrice, int quantity, long commissionMinor, UUID buyerUuid,
                        UUID sellerUuid, String escrowReference, long createdAt) {
            this.marketKey = marketKey;
            this.buyOrderId = buyOrderId;
            this.sellOrderId = sellOrderId;
            this.makerSide = makerSide;
            this.executionPrice = executionPrice;
            this.quantity = quantity;
            this.commissionMinor = commissionMinor;
            this.buyerUuid = buyerUuid;
            this.sellerUuid = sellerUuid;
            this.escrowReference = escrowReference;
            this.createdAt = createdAt;
            this.tradeId = UUID.randomUUID();
        }

        public Builder tradeId(UUID id) {
            this.tradeId = id;
            return this;
        }

        public Trade build() {
            long gross = executionPrice * (long) quantity;
            return new Trade(tradeId, marketKey, buyOrderId, sellOrderId, makerSide,
                    executionPrice, quantity, gross, commissionMinor, gross - commissionMinor,
                    buyerUuid, sellerUuid, escrowReference, TradeState.PENDING, createdAt, null, 0);
        }
    }
}
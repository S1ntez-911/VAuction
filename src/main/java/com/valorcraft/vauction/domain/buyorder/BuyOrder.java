package com.valorcraft.vauction.domain.buyorder;

import com.valorcraft.vauction.item.ItemSnapshot;

import java.util.Objects;
import java.util.UUID;

/**
 * Заявка на покупку (BuyOrder). Иммутабельна; частичное исполнение создаёт новый
 * экземпляр и сохраняется через optimistic lock ({@code version}).
 * <p>
 * Экономика: при создании покупатель замораживает funds под референс
 * {@code vauction:buy:<id>:<refEpoch>}; при исполнении части — вся заморозка
 * снимается через release, доля списывается, остаток замораживается под
 * refEpoch+1. refEpoch хранится здесь, остальные детали — в журнале операций.
 */
public record BuyOrder(
        UUID buyOrderId,
        UUID buyerUuid,
        ItemSnapshot item,
        long pricePerUnit,
        int totalRequested,
        int fulfilledAmount,
        boolean active,
        int refEpoch,
        long createdAt,
        long updatedAt,
        int version
) {

    public BuyOrder {
        Objects.requireNonNull(buyOrderId, "buyOrderId");
        Objects.requireNonNull(buyerUuid, "buyerUuid");
        Objects.requireNonNull(item, "item");
        if (pricePerUnit <= 0) {
            throw new IllegalArgumentException("pricePerUnit must be > 0");
        }
        if (totalRequested <= 0) {
            throw new IllegalArgumentException("totalRequested must be > 0");
        }
        if (fulfilledAmount < 0 || fulfilledAmount > totalRequested) {
            throw new IllegalArgumentException(
                    "fulfilledAmount must be in [0, totalRequested]");
        }
        if (refEpoch < 0) {
            throw new IllegalArgumentException("refEpoch must be >= 0");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }

    /* ------------------------------- transitions ------------------------------- */

    /** Остаток к исполнению. */
    public int remaining() {
        return totalRequested - fulfilledAmount;
    }

    /** Заявка полностью исполнена. */
    public boolean isDone() {
        return fulfilledAmount >= totalRequested;
    }

    /**
     * Зафиксировать исполнение {@code doneNow} единиц и, если заявка ещё активна,
     * перезаморозить остаток под {@code nextEpoch}. Возвращает новый экземпляр.
     */
    public BuyOrder markFulfilled(int doneNow, int nextEpoch, long now) {
        if (doneNow <= 0 || doneNow > remaining()) {
            throw new IllegalArgumentException("doneNow вне допустимого диапазона: " + doneNow);
        }
        int newFulfilled = fulfilledAmount + doneNow;
        boolean stillActive = newFulfilled < totalRequested;
        return new BuyOrder(buyOrderId, buyerUuid, item, pricePerUnit, totalRequested,
                newFulfilled, stillActive, stillActive ? nextEpoch : refEpoch, createdAt, now, version);
    }

    /** Деактивировать заявку (отмена/истечение) без изменения fulfilled. */
    public BuyOrder deactivate(long now) {
        if (!active) {
            throw new IllegalStateException("order already inactive");
        }
        return new BuyOrder(buyOrderId, buyerUuid, item, pricePerUnit, totalRequested,
                fulfilledAmount, false, refEpoch, createdAt, now, version);
    }

    /** Ссылка на escrow-референс текущей эпохи. */
    public String escrowReference() {
        return "vauction:buy:" + buyOrderId + ":" + refEpoch;
    }

    /** Builder для новой заявки (id пока не присвоен — пусть будет {@link UUID#randomUUID()}). */
    public static Builder newOrder(UUID buyerUuid, ItemSnapshot item, long pricePerUnit,
                                   int totalRequested, long createdAt) {
        return new Builder(buyerUuid, item, pricePerUnit, totalRequested, createdAt);
    }

    public static final class Builder {
        private final UUID buyerUuid;
        private final ItemSnapshot item;
        private final long pricePerUnit;
        private final int totalRequested;
        private final long createdAt;
        private UUID buyOrderId;
        private int fulfilledAmount;
        private int refEpoch;
        private boolean active = true;

        private Builder(UUID buyerUuid, ItemSnapshot item, long pricePerUnit,
                        int totalRequested, long createdAt) {
            this.buyerUuid = buyerUuid;
            this.item = item;
            this.pricePerUnit = pricePerUnit;
            this.totalRequested = totalRequested;
            this.createdAt = createdAt;
            this.buyOrderId = UUID.randomUUID();
        }

        public Builder orderId(UUID id) {
            this.buyOrderId = id;
            return this;
        }

        public Builder fulfilledAmount(int fulfilled) {
            this.fulfilledAmount = fulfilled;
            return this;
        }

        public Builder refEpoch(int epoch) {
            this.refEpoch = epoch;
            return this;
        }

        public Builder active(boolean a) {
            this.active = a;
            return this;
        }

        public BuyOrder build() {
            return new BuyOrder(buyOrderId, buyerUuid, item, pricePerUnit, totalRequested,
                    fulfilledAmount, active, refEpoch, createdAt, createdAt, 0);
        }
    }
}
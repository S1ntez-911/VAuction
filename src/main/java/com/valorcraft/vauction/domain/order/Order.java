package com.valorcraft.vauction.domain.order;

import com.valorcraft.vauction.item.ItemSnapshot;

import java.util.Objects;
import java.util.UUID;

/**
 * Домен-сущность ордера единого стакана. Иммутабельна; все переходы создают
 * новый экземпляр и сохраняются через optimistic lock ({@code version}).
 * <p>
 * Инварианты:
 * <ul>
 *   <li>{@code remainingQuantity + filledQuantity == originalQuantity};</li>
 *   <li>{@code 0 <= filledQuantity <= originalQuantity};</li>
 *   <li>суммы — всегда long в минимальных единицах, никаких double;</li>
 *   <li>{@code remainingQuantity == 0} ⇒ терминальный статус {@code FILLED}.</li>
 * </ul>
 */
public record Order(
        UUID orderId,
        UUID ownerUuid,
        OrderSide side,
        OrderStatus status,
        String marketKey,
        ItemSnapshot item,
        long pricePerUnit,
        int originalQuantity,
        int remainingQuantity,
        int filledQuantity,
        String escrowReference,
        int refEpoch,
        long createdAt,
        long updatedAt,
        int version
) {

    public Order {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(marketKey, "marketKey");
        Objects.requireNonNull(item, "item");
        if (pricePerUnit <= 0) {
            throw new IllegalArgumentException("pricePerUnit must be > 0");
        }
        if (originalQuantity <= 0) {
            throw new IllegalArgumentException("originalQuantity must be > 0");
        }
        if (filledQuantity < 0 || filledQuantity > originalQuantity) {
            throw new IllegalArgumentException("filledQuantity must be in [0, originalQuantity]");
        }
        if (remainingQuantity < 0 || remainingQuantity > originalQuantity) {
            throw new IllegalArgumentException("remainingQuantity must be in [0, originalQuantity]");
        }
        if (remainingQuantity + filledQuantity != originalQuantity) {
            throw new IllegalArgumentException("remainingQuantity + filledQuantity must equal originalQuantity");
        }
        if (refEpoch < 0) {
            throw new IllegalArgumentException("refEpoch must be >= 0");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }

    /** Активный ордер в стакане. */
    public boolean isActive() {
        return status == OrderStatus.ACTIVE;
    }

    /** Полностью исполнен. */
    public boolean isFilled() {
        return remainingQuantity == 0;
    }

    /**
     * Зафиксировать частичное/полное исполнение {@code done} единиц.
     * Возвращает новый экземпляр (CAS по version — в репозитории).
     */
    public Order markFilled(int done, long now) {
        if (done <= 0 || done > remainingQuantity) {
            throw new IllegalArgumentException("markFilled вне диапазона: done=" + done
                    + ", remaining=" + remainingQuantity);
        }
        int newRemaining = remainingQuantity - done;
        int newFilled = filledQuantity + done;
        return new Order(orderId, ownerUuid, side,
                newRemaining == 0 ? OrderStatus.FILLED : OrderStatus.ACTIVE,
                marketKey, item, pricePerUnit, originalQuantity, newRemaining, newFilled,
                escrowReference, refEpoch, createdAt, now, version);
    }

    /** Сменить эпоху escrow (после settlement старой эпохи). */
    public Order withRefEpoch(int nextEpoch, String newReference, long now) {
        if (nextEpoch <= refEpoch) {
            throw new IllegalArgumentException("nextEpoch must be > refEpoch");
        }
        return new Order(orderId, ownerUuid, side, status, marketKey, item, pricePerUnit,
                originalQuantity, remainingQuantity, filledQuantity, newReference,
                nextEpoch, createdAt, now, version);
    }

    /**
     * Откатить потребление {@code done} единиц (компенсация при сбое расчёта S3):
     * остаток увеличивается, filled уменьшается, статус возвращается в ACTIVE.
     */
    public Order restore(int done, long now) {
        if (done <= 0 || done > filledQuantity) {
            throw new IllegalArgumentException("restore вне диапазона: done=" + done
                    + ", filled=" + filledQuantity);
        }
        OrderStatus restored = status == OrderStatus.FILLED || status == OrderStatus.ACTIVE
                ? OrderStatus.ACTIVE : status;
        return new Order(orderId, ownerUuid, side, restored, marketKey, item, pricePerUnit,
                originalQuantity, remainingQuantity + done, filledQuantity - done,
                escrowReference, refEpoch, createdAt, now, version);
    }

    /** Деактивация ордера (отмена). Состояние эскроу не трогает (это слой сервиса). */
    public Order cancelled(long now) {
        return new Order(orderId, ownerUuid, side, OrderStatus.CANCELLED, marketKey, item,
                pricePerUnit, originalQuantity, remainingQuantity, filledQuantity,
                escrowReference, refEpoch, createdAt, now, version);
    }

    /** Перевод в ручное ревью (не торгуется, данные не теряются). */
    public Order toManualReview(long now) {
        return new Order(orderId, ownerUuid, side, OrderStatus.MANUAL_REVIEW, marketKey, item,
                pricePerUnit, originalQuantity, remainingQuantity, filledQuantity,
                escrowReference, refEpoch, createdAt, now, version);
    }

    /** Экспирация (снимается с книги, отдача остатка — слой сервиса). */
    public Order expired(long now) {
        return new Order(orderId, ownerUuid, side, OrderStatus.EXPIRED, marketKey, item,
                pricePerUnit, originalQuantity, remainingQuantity, filledQuantity,
                escrowReference, refEpoch, createdAt, now, version);
    }

    /** Builder для нового ордера (orderId ещё неизвестен). */
    public static Builder newOrder(UUID ownerUuid, OrderSide side, String marketKey,
                                   ItemSnapshot item, long pricePerUnit, int quantity, long now) {
        return new Builder(ownerUuid, side, marketKey, item, pricePerUnit, quantity, now);
    }

    public static final class Builder {
        private final UUID ownerUuid;
        private final OrderSide side;
        private final String marketKey;
        private final ItemSnapshot item;
        private final long pricePerUnit;
        private final int quantity;
        private final long createdAt;
        private UUID orderId;
        private int refEpoch;
        private String escrowReference;
        private int filledQuantity;

        private Builder(UUID ownerUuid, OrderSide side, String marketKey, ItemSnapshot item,
                        long pricePerUnit, int quantity, long createdAt) {
            this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            this.side = Objects.requireNonNull(side, "side");
            this.marketKey = Objects.requireNonNull(marketKey, "marketKey");
            this.item = Objects.requireNonNull(item, "item");
            this.pricePerUnit = pricePerUnit;
            this.quantity = quantity;
            this.createdAt = createdAt;
            this.orderId = UUID.randomUUID();
        }

        public Builder orderId(UUID id) {
            this.orderId = id;
            return this;
        }

        public Builder refEpoch(int epoch) {
            this.refEpoch = epoch;
            return this;
        }

        public Builder escrowReference(String reference) {
            this.escrowReference = reference;
            return this;
        }

        public Builder filledQuantity(int filled) {
            this.filledQuantity = filled;
            return this;
        }

        public Order build() {
            return new Order(orderId, ownerUuid, side, OrderStatus.ACTIVE, marketKey, item,
                    pricePerUnit, quantity, quantity - filledQuantity, filledQuantity,
                    escrowReference, refEpoch, createdAt, createdAt, 0);
        }
    }
}
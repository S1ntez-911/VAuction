package com.valorcraft.vauction.application;

import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderStatus;
import com.valorcraft.vauction.item.ItemSnapshot;

import java.util.UUID;

/** One relevant player-facing entry for the unified "My" feed. */
public record PlayerMarketActivity(
        Kind kind,
        UUID orderId,
        long deliveryId,
        OrderSide side,
        OrderStatus orderStatus,
        DeliveryType deliveryType,
        ItemSnapshot item,
        long pricePerUnit,
        int originalQuantity,
        int remainingQuantity,
        int filledQuantity,
        long sortTime) {

    public enum Kind { ORDER, DELIVERY }

    public boolean claimable() {
        return kind == Kind.DELIVERY;
    }

    public boolean manageable() {
        return kind == Kind.ORDER && orderStatus == OrderStatus.ACTIVE;
    }
}

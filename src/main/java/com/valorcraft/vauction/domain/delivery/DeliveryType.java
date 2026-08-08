package com.valorcraft.vauction.domain.delivery;

/**
 * Повод создания delivery (выдача предмета игроку).
 */
public enum DeliveryType {
    PURCHASED,
    CANCELLED_RETURN,
    EXPIRED_RETURN,
    ADMIN_RETURN,
    COMPENSATION
}
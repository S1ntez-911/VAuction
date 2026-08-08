package com.valorcraft.vauction.domain.order;

/** Durable, mutually exclusive work currently owning an order. */
public enum OrderProcessingState {
    NONE,
    RESERVE,
    ITEM_LOCK,
    FILL,
    CANCEL,
    EXPIRE
}

package com.valorcraft.vauction.domain.delivery;

/**
 * Состояние delivery:
 * PENDING → CLAIMABLE → CLAIMING → CLAIMED;
 * PENDING/CLAIMABLE/CLAIMING → FAILED.
 */
public enum DeliveryState {
    PENDING,
    CLAIMABLE,
    CLAIMING,
    CLAIMED,
    FAILED
}
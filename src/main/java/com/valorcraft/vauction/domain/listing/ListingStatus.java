package com.valorcraft.vauction.domain.listing;

/**
 * Жизненный цикл лота.
 * <p>
 * ACTIVE → RESERVED → SOLD;
 * ACTIVE → CANCELLED / EXPIRED / FAILED.
 * Завершённые лоты не удаляются (ретеншн).
 */
public enum ListingStatus {
    ACTIVE,
    RESERVED,
    SOLD,
    CANCELLED,
    EXPIRED,
    FAILED
}
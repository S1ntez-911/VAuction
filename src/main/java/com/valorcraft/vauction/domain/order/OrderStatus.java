package com.valorcraft.vauction.domain.order;

/**
 * Статус ордера.
 * <ul>
 *   <li>{@code ACTIVE} — стоит в стакане (remaining_quantity &gt; 0);</li>
 *   <li>{@code FILLED} — remaining_quantity = 0 (терминальный);</li>
 *   <li>{@code CANCELLED} — отменён владельцем (терминальный);</li>
 *   <li>{@code EXPIRED} — истёк по времени (терминальный);</li>
 *   <li>{@code MANUAL_REVIEW} — сбой и требуется вмешательство (не торгуется);</li>
 *   <li>{@code LEGACY_LOCKED} — legacy-строка, не участвует в новом матчинге.</li>
 * </ul>
 */
public enum OrderStatus {
    ACTIVE,
    FILLED,
    CANCELLED,
    EXPIRED,
    MANUAL_REVIEW,
    LEGACY_LOCKED
}
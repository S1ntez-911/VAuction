package com.valorcraft.vauction.config;

/**
 * Режим политики предметов.
 * <p>
 * BLACKLIST — запрещены {@code blockedItems}/{@code blockedTags};
 * WHITELIST — разрешены только {@code whitelistedItems}/{@code whitelistedTags};
 * NONE — проверки списков отключены (для отладки; на проде не рекомендуется).
 */
public enum ItemPolicyMode {
    BLACKLIST,
    WHITELIST,
    NONE
}
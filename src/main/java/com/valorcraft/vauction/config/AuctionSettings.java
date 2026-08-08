package com.valorcraft.vauction.config;

import java.util.List;
import java.util.Objects;

/**
 * Неизменяемый снимок настроек аукциона на момент операции.
 * <p>
 * Обновляется из {@link AuctionConfig} (ForgeConfigSpec, файл vauction-server.toml)
 * при старте сервера, берётся в операции (комиссия фиксируется для лота при
 * создании и сохраняется в БД). Деньги — всегда в минимальных единицах (long),
 * комиссия — в базисных пунктах (int bps): никаких double в хранящихся суммах.
 */
public record AuctionSettings(
        boolean enabled,
        int listingDurationHours,
        int maxActiveListingsPerPlayer,
        int maxBuyOrdersPerPlayer,
        long listingFeeMinor,
        int commissionBps,
        int expiredRetentionDays,
        int historyRetentionDays,
        boolean allowSelfPurchase,
        boolean allowContainersWithContents,
        boolean blockCustomNbt,
        boolean allowEnchantedBooks,
        int maxCompressedItemBytes,
        int maxUncompressedItemBytes,
        int sellOrderExpiryDays,
        int buyOrderExpiryDays,
        ItemPolicyMode itemPolicyMode,
        List<String> blockedItems,
        List<String> blockedTags,
        List<String> whitelistedItems,
        List<String> whitelistedTags
) {

    public AuctionSettings {
        Objects.requireNonNull(itemPolicyMode, "itemPolicyMode");
        blockedItems = blockedItems == null ? List.of() : List.copyOf(blockedItems);
        blockedTags = blockedTags == null ? List.of() : List.copyOf(blockedTags);
        whitelistedItems = whitelistedItems == null ? List.of() : List.copyOf(whitelistedItems);
        whitelistedTags = whitelistedTags == null ? List.of() : List.copyOf(whitelistedTags);
    }

    public static AuctionSettings defaults() {
        return new AuctionSettings(
                true,                   // enabled
                48,                     // listingDurationHours
                10,                     // maxActiveListingsPerPlayer
                10,                     // maxBuyOrdersPerPlayer
                0L,                     // listingFeeMinor
                250,                    // commissionBps (2.5%)
                30,                     // expiredRetentionDays
                90,                     // historyRetentionDays
                false,                  // allowSelfPurchase
                false,                  // allowContainersWithContents
                false,                  // blockCustomNbt: MarketKey разделяет по полному NBT, контент не теряется
                true,                   // allowEnchantedBooks
                262_144,                // maxCompressedItemBytes
                2_097_152,              // maxUncompressedItemBytes
                7,                      // sellOrderExpiryDays
                3,                      // buyOrderExpiryDays
                ItemPolicyMode.BLACKLIST,
                List.of("minecraft:shulker_box",
                        "minecraft:written_book", "minecraft:enchanted_book"),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
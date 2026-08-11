package com.valorcraft.vauction.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Серверный конфиг аукциона ({@code config/VMods/VAuction/vauction-server.toml}) на ForgeConfigSpec.
 * <p>
 * Снимок значений берётся через {@link #snapshot()} (immutable {@link AuctionSettings})
 * — он и передаётся в сервисы, в операции сохраняется в БД. Комиссия задаётся
 * в процентах (double) только в конфиге; в домене — всегда basis points (int).
 * <p>
 * Все поля читаются с серверного потока или других потоков на этапе конфифгурации —
 * ForgeConfigSpec сам хорошо потокобезопасен.
 */
public final class AuctionConfig {

    private static final ForgeConfigSpec SPEC;
    private static final AuctionSpec VALUES;

    private AuctionConfig() {}

    private static final class AuctionSpec {
        final ForgeConfigSpec.BooleanValue enabled;
        final ForgeConfigSpec.IntValue listingDurationHours;
        final ForgeConfigSpec.IntValue maxSellOrdersPerPlayer;
        final ForgeConfigSpec.IntValue maxBuyOrdersPerPlayer;
        final ForgeConfigSpec.LongValue listingFeeMinor;
        final ForgeConfigSpec.DoubleValue commissionPercent;
        final ForgeConfigSpec.IntValue expiredRetentionDays;
        final ForgeConfigSpec.IntValue historyRetentionDays;
        final ForgeConfigSpec.BooleanValue allowSelfPurchase;
        final ForgeConfigSpec.IntValue sellOrderExpiryDays;
        final ForgeConfigSpec.IntValue buyOrderExpiryDays;
        final ForgeConfigSpec.IntValue maxCompressedItemBytes;
        final ForgeConfigSpec.IntValue maxUncompressedItemBytes;
        final ForgeConfigSpec.BooleanValue allowContainersWithContents;
        final ForgeConfigSpec.BooleanValue blockCustomNbt;
        final ForgeConfigSpec.BooleanValue allowEnchantedBooks;
        final ForgeConfigSpec.ConfigValue<List<? extends String>> blacklistItems;
        final ForgeConfigSpec.ConfigValue<List<? extends String>> blacklistTags;
        final ForgeConfigSpec.ConfigValue<List<? extends String>> whitelistItems;
        final ForgeConfigSpec.ConfigValue<List<? extends String>> whitelistTags;
        final ForgeConfigSpec.EnumValue<ItemPolicyMode> itemPolicyMode;

        private AuctionSpec(ForgeConfigSpec.Builder b) {
            b.comment("VAuction — параметры аукциона (серверные)").push("general");

            enabled = b.comment("Общий выключатель аукциона.")
                    .define("enabled", true);
            listingDurationHours = b
                    .comment("Время жизни лота на продажу в часах (0 = бессрочно).")
                    .defineInRange("listing_duration_hours", 48, 0, 24 * 365);
            maxSellOrdersPerPlayer = b
                    .comment("Максимум активных лотов на продажу у одного игрока.")
                    .defineInRange("max_sell_orders_per_player", 10, 1, 100);
            maxBuyOrdersPerPlayer = b
                    .comment("Максимум активных заявок на покупку у одного игрока.")
                    .defineInRange("max_buy_orders_per_player", 10, 1, 100);
            listingFeeMinor = b
                    .comment("Плата за выставление лота в минимальных единицах валюты (0 = бесплатно).")
                    .defineInRange("listing_fee_minor", 0L, 0L, Long.MAX_VALUE);
            commissionPercent = b
                    .comment("Комиссия сервера в процентах (0.0 - 100.0), удерживается с продавца.")
                    .defineInRange("commission_percent", 2.5d, 0.0d, 100.0d);
            sellOrderExpiryDays = b
                    .comment("Макс. срок существования лота в днях, после которого возврат продавцу.")
                    .defineInRange("sell_order_expiry_days", 7, 0, 365);
            buyOrderExpiryDays = b
                    .comment("Макс. срок существования заявки на покупку в днях (0 = бессрочно).")
                    .defineInRange("buy_order_expiry_days", 3, 0, 365);
            expiredRetentionDays = b
                    .comment("Сколько дней хранить записи исполненных/отменённых лотов (история).")
                    .defineInRange("expired_retention_days", 30, 1, 3650);
            historyRetentionDays = b
                    .comment("Сколько дней хранить журнал транзакций.")
                    .defineInRange("history_retention_days", 90, 1, 3650);
            allowSelfPurchase = b
                    .comment("Разрешить игроку покупать собственный лот.")
                    .define("allow_self_purchase", false);
            b.pop();

            b.comment("Предметы и NBT").push("items");
            allowContainersWithContents = b
                    .comment("Разрешать шалкеры/контейнеры с содержимым (опасно для баланса).")
                    .define("allow_containers_with_contents", false);
            blockCustomNbt = b
                    .comment("Запрещать предметы с нестандартным NBT (кроме повреждений).",
                            "false = предметы с тегами торгуются: MarketKey разделяет стакан по полному NBT, контент не теряется.")
                    .define("block_custom_nbt", false);
            allowEnchantedBooks = b
                    .comment("Разрешать книги зачарований даже при block_custom_nbt=true.")
                    .define("allow_enchanted_books", true);
            itemPolicyMode = b
                    .comment("Режим политики предметов: BLACKLIST или WHITELIST.")
                    .defineEnum("item_policy_mode", ItemPolicyMode.BLACKLIST);
            blacklistItems = b
                    .comment("Полные ID предметов, запрещённые к торговле.",
                            "Пример: minecraft:shulker_box, minecraft:written_book")
                    .defineListAllowEmpty("blacklist_items",
                            Arrays.asList("minecraft:shulker_box",
                                    "minecraft:written_book", "minecraft:enchanted_book"),
                            s -> s instanceof String s2 && s2.matches("[a-z0-9_.]+:[a-z0-9_./-]+"));
            blacklistTags = b
                    .comment("Теги предметов, запрещённые к торговле (например minecraft:black_dyes).")
                    .defineListAllowEmpty("blacklist_tags", List.of(),
                            s -> s instanceof String s2 && s2.matches("[a-z0-9_.]+:[a-z0-9_./-]+"));
            whitelistItems = b
                    .comment("Полные ID предметов, разрешённые в режиме WHITELIST.")
                    .defineListAllowEmpty("whitelist_items", List.of(),
                            s -> s instanceof String s2 && s2.matches("[a-z0-9_.]+:[a-z0-9_./-]+"));
            whitelistTags = b
                    .comment("Теги предметов, разрешённые в режиме WHITELIST.")
                    .defineListAllowEmpty("whitelist_tags", List.of(),
                            s -> s instanceof String s2 && s2.matches("[a-z0-9_.]+:[a-z0-9_./-]+"));
            maxCompressedItemBytes = b
                    .comment("Максимум байт сжатого NBT предмета (защита от дупов размера).")
                    .defineInRange("max_compressed_item_bytes", 262_144, 1024, 16_777_216);
            maxUncompressedItemBytes = b
                    .comment("Максимум байт несжатого NBT предмета.")
                    .defineInRange("max_uncompressed_item_bytes", 2_097_152, 1024, 128_000_000);
            b.pop();
        }
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        VALUES = new AuctionSpec(builder);
        SPEC = builder.build();
    }

    /** Регистрация файла конфигурации (в конструкторе мода). */
    public static void register() {
        try {
            VAuctionConfigPaths.file(FMLPaths.CONFIGDIR.get(), "vauction-server.toml");
            VAuctionConfigPaths.migrateLegacyWorldFile(FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get(),
                    "vauction-server.toml");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare config/VMods/VAuction", e);
        }
        // COMMON is intentional: Forge SERVER configs live inside each world's
        // serverconfig directory, while ValorCraft keeps all mod configs together.
        // The values are still consumed exclusively by the dedicated server.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC,
                VAuctionConfigPaths.forgeFileName("vauction-server.toml"));
    }

    /** Актуальный снимок настроек (для сервисов и операций). */
    public static AuctionSettings snapshot() {
        AuctionSpec v = VALUES;
        return new AuctionSettings(
                v.enabled.get(),
                v.listingDurationHours.get(),
                v.maxSellOrdersPerPlayer.get(),
                v.maxBuyOrdersPerPlayer.get(),
                v.listingFeeMinor.get(),
                commissionBps(),
                v.expiredRetentionDays.get(),
                v.historyRetentionDays.get(),
                v.allowSelfPurchase.get(),
                v.allowContainersWithContents.get(),
                v.blockCustomNbt.get(),
                v.allowEnchantedBooks.get(),
                v.maxCompressedItemBytes.get(),
                v.maxUncompressedItemBytes.get(),
                v.sellOrderExpiryDays.get(),
                v.buyOrderExpiryDays.get(),
                v.itemPolicyMode.get(),
                listOf(v.blacklistItems.get()),
                listOf(v.blacklistTags.get()),
                listOf(v.whitelistItems.get()),
                listOf(v.whitelistTags.get()));
    }

    public static boolean isEnabled() {
        return VALUES.enabled.get();
    }

    public static int maxSellOrdersPerPlayer() {
        return VALUES.maxSellOrdersPerPlayer.get();
    }

    public static int maxBuyOrdersPerPlayer() {
        return VALUES.maxBuyOrdersPerPlayer.get();
    }

    public static int sellOrderExpiryDays() {
        return VALUES.sellOrderExpiryDays.get();
    }

    public static int buyOrderExpiryDays() {
        return VALUES.buyOrderExpiryDays.get();
    }

    /** Комиссия в базисных пунктах (из процентного поля конфига). */
    public static int commissionBps() {
        return (int) Math.round(Math.max(0.0d, Math.min(100.0d, VALUES.commissionPercent.get())) * 100.0d);
    }

    private static List<String> listOf(List<? extends String> src) {
        return src == null ? List.of() : List.copyOf(src);
    }
}

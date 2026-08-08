package com.valorcraft.exchange.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.Arrays;
import java.util.List;

/**
 * Конфигурация биржи ({@code exchange_core.toml}).
 * <p>
 * Комиссия — в процентах, удерживается с продавца при исполнении лота/заявки и
 * накапливается в {@code serverCommission} (доступна через админ-команду).
 */
public final class ExchangeConfig {

    private static final ForgeConfigSpec SPEC;
    private static final ExchangeSpec VALUES;

    private ExchangeConfig() {}

    private static final class ExchangeSpec {
        final ForgeConfigSpec.DoubleValue commissionPercent;
        final ForgeConfigSpec.IntValue maxSellOrdersPerPlayer;
        final ForgeConfigSpec.IntValue maxBuyOrdersPerPlayer;
        final ForgeConfigSpec.IntValue maxTransactionHistory;
        final ForgeConfigSpec.IntValue sellOrderExpiryDays;
        final ForgeConfigSpec.IntValue buyOrderExpiryDays;
        final ForgeConfigSpec.ConfigValue<List<? extends String>> blacklistItems;
        final ForgeConfigSpec.BooleanValue blockCustomNbt;
        final ForgeConfigSpec.BooleanValue allowEnchantedBooks;

        private ExchangeSpec(ForgeConfigSpec.Builder builder) {
            builder.comment("Биржа Ресурсов — параметры торговли").push("general");

            commissionPercent = builder
                    .comment("Комиссия сервера в процентах (0.0 - 100.0), удерживается с продавца.")
                    .defineInRange("commission_percent", 2.5d, 0.0d, 100.0d);
            maxSellOrdersPerPlayer = builder
                    .comment("Максимум активных лотов на продажу у одного игрока.")
                    .defineInRange("max_sell_orders_per_player", 10, 1, 100);
            maxBuyOrdersPerPlayer = builder
                    .comment("Максимум активных заявок на покупку у одного игрока.")
                    .defineInRange("max_buy_orders_per_player", 10, 1, 100);
            maxTransactionHistory = builder
                    .comment("Лимит хранимой истории транзакций (последние N).")
                    .defineInRange("max_transaction_history", 100, 10, 1000);
            sellOrderExpiryDays = builder
                    .comment("Время жизни лота на продажу в днях (0 = бессрочно).")
                    .defineInRange("sell_order_expiry_days", 7, 0, 365);
            buyOrderExpiryDays = builder
                    .comment("Время жизни заявки на покупку в днях (0 = бессрочно).")
                    .defineInRange("buy_order_expiry_days", 3, 0, 365);
            builder.pop();

            builder.comment("Запрещённые для торговли предметы").push("blacklist");
            blacklistItems = builder
                    .comment("Полные ID предметов, запрещённые к торговле.",
                            "Пример: minecraft:shulker_box, minecraft:written_book")
                    .defineListAllowEmpty("blacklist_items",
                            Arrays.asList("minecraft:shulker_box",
                                    "minecraft:written_book", "minecraft:enchanted_book"),
                            s -> s instanceof String s2 && s2.matches("[a-z0-9_.]+:[a-z0-9_./-]+"));
            blockCustomNbt = builder
                    .comment("Запретить торговлю предметами с NBT (кроме повреждений):",
                    "true = шалкеры с предметами, книги с текстом и т.п. на биржу не попадут.")
                    .define("block_custom_nbt", true);
            allowEnchantedBooks = builder
                    .comment("Разрешить книги зачарований (даже при включённом block_custom_nbt).")
                    .define("allow_enchanted_books", true);
            builder.pop();
        }
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        VALUES = new ExchangeSpec(builder);
        SPEC = builder.build();
    }

    /** Регистрация файла конфигурации. Должен вызываться в конструкторе мода. */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    public static double commissionPercent() {
        return Math.max(0.0d, Math.min(100.0d, VALUES.commissionPercent.get()));
    }

    public static int maxSellOrdersPerPlayer() {
        return VALUES.maxSellOrdersPerPlayer.get();
    }

    public static int maxBuyOrdersPerPlayer() {
        return VALUES.maxBuyOrdersPerPlayer.get();
    }

    public static int maxTransactionHistory() {
        return VALUES.maxTransactionHistory.get();
    }

    public static int sellOrderExpiryDays() {
        return VALUES.sellOrderExpiryDays.get();
    }

    public static int buyOrderExpiryDays() {
        return VALUES.buyOrderExpiryDays.get();
    }

    public static boolean isBlacklisted(String itemId) {
        return VALUES.blacklistItems.get().contains(itemId);
    }

    public static boolean blockCustomNbt() {
        return VALUES.blockCustomNbt.get();
    }

    public static boolean allowEnchantedBooks() {
        return VALUES.allowEnchantedBooks.get();
    }
}
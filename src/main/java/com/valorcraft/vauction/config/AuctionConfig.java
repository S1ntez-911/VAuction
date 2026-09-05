package com.valorcraft.vauction.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class AuctionConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_LISTINGS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue LISTING_DURATION_HOURS;
    public static final ForgeConfigSpec.IntValue HISTORY_RETENTION_DAYS;
    public static final ForgeConfigSpec.LongValue MIN_PRICE;
    public static final ForgeConfigSpec.LongValue MAX_PRICE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> FORBIDDEN_ITEMS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CATEGORY_OVERRIDES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CATEGORY_DEFINITIONS;
    public static final ForgeConfigSpec.ConfigValue<String> PREVIOUS_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> NEXT_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> INFO_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> FILTER_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> BACKGROUND_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> MY_LISTINGS_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> ARCHIVE_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> REFRESH_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> RESET_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> SORT_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> BACK_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> CLAIM_ALL_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> HISTORY_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> SOLD_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> NO_MONEY_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> CONFIRM_YES_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> CONFIRM_NO_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> CONFIRM_BACKGROUND_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> FLUID_PREVIEW_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> SALE_NOTIFICATION;
    public static final ForgeConfigSpec.BooleanValue SOUNDS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> SALE_SOUND;
    public static final ForgeConfigSpec.ConfigValue<String> PURCHASE_SOUND;
    public static final ForgeConfigSpec.ConfigValue<String> ACTION_SOUND;
    public static final ForgeConfigSpec.DoubleValue SOUND_VOLUME;
    public static final ForgeConfigSpec.DoubleValue SOUND_PITCH;
    public static final ForgeConfigSpec.IntValue QUERY_CACHE_ENTRIES;
    public static final ForgeConfigSpec.IntValue EXPIRY_SCAN_INTERVAL_MS;
    public static final ForgeConfigSpec.IntValue MENU_CLICK_COOLDOWN_MS;
    public static final ForgeConfigSpec.IntValue DATABASE_BUSY_TIMEOUT_MS;
    public static final ForgeConfigSpec.ConfigValue<String> THEME_PRIMARY;
    public static final ForgeConfigSpec.ConfigValue<String> THEME_SECONDARY;
    public static final ForgeConfigSpec.ConfigValue<String> THEME_SUCCESS;
    public static final ForgeConfigSpec.ConfigValue<String> THEME_DANGER;
    public static final ForgeConfigSpec.ConfigValue<String> THEME_MUTED;
    public static final ForgeConfigSpec.ConfigValue<String> THEME_TEXT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("auction");
        MAX_LISTINGS_PER_PLAYER = builder.comment("Максимум активных лотов одного игрока")
                .defineInRange("maxListingsPerPlayer", 27, 1, 1000);
        LISTING_DURATION_HOURS = builder.comment("Срок жизни лота в часах")
                .defineInRange("listingDurationHours", 72, 1, 24 * 365);
        HISTORY_RETENTION_DAYS = builder.comment("Сколько дней хранить завершённые лоты в SQLite; 0 удаляет сразу")
                .defineInRange("historyRetentionDays", 14, 0, 3650);
        MIN_PRICE = builder.comment("Минимальная цена в минимальных единицах VEconomy")
                .defineInRange("minimumPriceMinor", 1L, 1L, Long.MAX_VALUE);
        MAX_PRICE = builder.comment("Максимальная цена в минимальных единицах VEconomy")
                .defineInRange("maximumPriceMinor", 1_000_000_000_000L, 1L, Long.MAX_VALUE);
        FORBIDDEN_ITEMS = builder.comment("ID предметов, которые запрещено выставлять")
                .defineList("forbiddenItems", List.of(), value -> value instanceof String);
        CATEGORY_OVERRIDES = builder.comment(
                        "Ручная категория: item_id=CATEGORY, #tag_id=CATEGORY или modid:*=CATEGORY",
                        "Категории: TOOLS, WEAPONS, ARMOR, FOOD, UNIQUE, ENCHANTING, ALCHEMY, POTIONS, BLOCKS, DECORATIVE, MECHANISMS, GEMS, VEGETATION, MOB_DROPS, MISC")
                .defineList("categoryOverrides", List.of(), value -> value instanceof String);
        CATEGORY_DEFINITIONS = builder.comment("Названия и иконки категорий: CATEGORY=Название|item_id")
                .defineList("categoryDefinitions", List.of(
                        "ALL=Все товары|minecraft:chest", "TOOLS=Инструменты|minecraft:iron_pickaxe",
                        "WEAPONS=Оружие|minecraft:iron_sword", "ARMOR=Броня|minecraft:iron_chestplate",
                        "FOOD=Еда|minecraft:cooked_beef", "UNIQUE=Уникальные предметы|minecraft:nether_star",
                        "ENCHANTING=Зачарование|minecraft:enchanted_book", "ALCHEMY=Алхимия|minecraft:brewing_stand",
                        "POTIONS=Зелья|minecraft:potion", "BLOCKS=Блоки|minecraft:bricks",
                        "DECORATIVE=Декоративные предметы|minecraft:painting", "MECHANISMS=Механизмы|minecraft:redstone",
                        "GEMS=Драгоценности|minecraft:diamond", "VEGETATION=Растительность|minecraft:oak_sapling",
                        "MOB_DROPS=Лут с мобов|minecraft:rotten_flesh", "MISC=Разное|minecraft:paper"),
                        value -> value instanceof String);
        builder.pop();

        builder.comment("Все служебные предметы GUI. Разрешены ID предметов Minecraft и модпака; тексты и lore находятся в lang/ru_ru.json")
                .push("interface");
        PREVIOUS_ITEM = builder.comment("Иконки основной панели; ключи V2 сохранены для совместимости с конфигом VAuction 1.0.3")
                .define("previousButtonItemV2", "minecraft:gray_dye");
        NEXT_ITEM = builder.define("nextButtonItemV2", "minecraft:yellow_dye");
        INFO_ITEM = builder.define("infoButtonItemV2", "minecraft:nether_star");
        FILTER_ITEM = builder.define("filterButtonItemV2", "minecraft:chest_minecart");
        BACKGROUND_ITEM = builder.comment("Заполнитель нижней панели меню")
                .define("backgroundItem", "minecraft:gray_stained_glass_pane");
        MY_LISTINGS_ITEM = builder.define("myListingsItem", "minecraft:chest");
        ARCHIVE_ITEM = builder.define("archiveItem", "minecraft:barrel");
        REFRESH_ITEM = builder.define("refreshItem", "minecraft:emerald");
        RESET_ITEM = builder.define("resetFiltersItem", "minecraft:name_tag");
        SORT_ITEM = builder.define("sortItem", "minecraft:hopper");
        BACK_ITEM = builder.define("backItem", "minecraft:arrow");
        CLAIM_ALL_ITEM = builder.define("claimAllItem", "minecraft:hopper");
        HISTORY_ITEM = builder.define("historyItem", "minecraft:book");
        SOLD_ITEM = builder.comment("Временная замена карточки, если лот уже куплен")
                .define("soldItem", "minecraft:barrier");
        NO_MONEY_ITEM = builder.comment("Временная замена карточки при недостатке средств")
                .define("noMoneyItem", "minecraft:barrier");
        CONFIRM_YES_ITEM = builder.define("confirmYesItem", "minecraft:lime_stained_glass_pane");
        CONFIRM_NO_ITEM = builder.define("confirmNoItem", "minecraft:red_stained_glass_pane");
        CONFIRM_BACKGROUND_ITEM = builder.define("confirmBackgroundItem", "minecraft:light_gray_stained_glass_pane");
        FLUID_PREVIEW_ITEM = builder.comment("Иконка жидкости в просмотре содержимого контейнера")
                .define("fluidPreviewItem", "minecraft:bucket");
        builder.pop();

        builder.push("messages");
        SALE_NOTIFICATION = builder.define("offlineSaleNotification",
                "Ваш товар {item} купили за {price}. Деньги уже зачислены.");
        builder.pop();

        builder.comment("Звуковые уведомления. Используются ID звуков Minecraft/модов")
                .push("sounds");
        SOUNDS_ENABLED = builder.define("enabled", true);
        SALE_SOUND = builder.define("saleSound", "minecraft:entity.player.levelup");
        PURCHASE_SOUND = builder.define("purchaseSound", "minecraft:entity.experience_orb.pickup");
        ACTION_SOUND = builder.define("actionSound", "minecraft:ui.button.click");
        SOUND_VOLUME = builder.defineInRange("volume", 0.8D, 0.0D, 4.0D);
        SOUND_PITCH = builder.defineInRange("pitch", 1.0D, 0.1D, 2.0D);
        builder.pop();

        builder.comment("Оптимизация аукциона под одновременную работу большого числа игроков")
                .push("performance");
        QUERY_CACHE_ENTRIES = builder.comment("Максимум кешированных вариантов сортировки и фильтрации")
                .defineInRange("queryCacheEntries", 512, 16, 8192);
        EXPIRY_SCAN_INTERVAL_MS = builder.comment("Минимальный интервал между проверками истёкших лотов")
                .defineInRange("expiryScanIntervalMs", 1000, 100, 60000);
        MENU_CLICK_COOLDOWN_MS = builder.comment("Защита GUI от спама пакетами; 0 отключает")
                .defineInRange("menuClickCooldownMs", 120, 0, 2000);
        DATABASE_BUSY_TIMEOUT_MS = builder.comment("Максимальное ожидание блокировки SQLite на серверном потоке")
                .defineInRange("databaseBusyTimeoutMs", 250, 0, 2000);
        builder.pop();

        builder.comment("Фирменная палитра ValorCraft. Формат: #RRGGBB; применяется ко всему интерфейсу и чату VAuction")
                .push("theme");
        THEME_PRIMARY = builder.comment("Основное фирменное золото ValorCraft")
                .define("primary", "#D4A84A");
        THEME_SECONDARY = builder.comment("Светлый золотой акцент ValorCraft")
                .define("secondary", "#FFD35B");
        THEME_SUCCESS = builder.define("success", "#75F09A");
        THEME_DANGER = builder.define("danger", "#FF8278");
        THEME_MUTED = builder.define("muted", "#889AAA");
        THEME_TEXT = builder.define("text", "#E8E6DA");
        builder.pop();
        SPEC = builder.build();
    }

    private AuctionConfig() {}

    /** Reload only our registered config, preserving Forge's file watcher and clearing value caches. */
    public static void reload() {
        var config = net.minecraftforge.fml.config.ConfigTracker.INSTANCE.fileMap()
                .get("VMods/VAuction/VAuction.toml");
        if (config == null || !(config.getConfigData() instanceof com.electronwill.nightconfig.core.file.CommentedFileConfig file))
            throw new IllegalStateException("VAuction config is not loaded");
        if (!java.nio.file.Files.isRegularFile(config.getFullPath()))
            throw new IllegalStateException("VAuction.toml does not exist");
        // Parse and validate separately before touching the active configuration.
        try (var candidate = com.electronwill.nightconfig.core.file.CommentedFileConfig.builder(config.getFullPath()).sync().build()) {
            candidate.load();
            validateReload(candidate);
        }
        file.load();
        SPEC.afterReload();
    }

    static void validateReload(com.electronwill.nightconfig.core.CommentedConfig candidate) {
        // Comment edits are harmless; only corrections to actual values reject the reload.
        SPEC.correct(candidate, (action, path, oldValue, newValue) -> {
            throw new IllegalArgumentException("Invalid or missing VAuction setting: " + String.join(".", path));
        });
    }
}

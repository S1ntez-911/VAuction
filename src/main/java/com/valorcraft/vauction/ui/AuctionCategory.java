package com.valorcraft.vauction.ui;

import com.valorcraft.vauction.config.AuctionConfig;
import com.valorcraft.vauction.model.AuctionListing;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Human-facing TM2 category order. Modded items are classified by type/tags, not namespace. */
public enum AuctionCategory {
    ALL("Все товары", Items.CHEST), TOOLS("Инструменты", Items.IRON_PICKAXE),
    WEAPONS("Оружие", Items.IRON_SWORD), ARMOR("Броня", Items.IRON_CHESTPLATE),
    FOOD("Еда", Items.COOKED_BEEF), UNIQUE("Уникальные предметы", Items.NETHER_STAR),
    ENCHANTING("Зачарование", Items.ENCHANTED_BOOK), ALCHEMY("Алхимия", Items.BREWING_STAND),
    POTIONS("Зелья", Items.POTION), BLOCKS("Блоки", Items.BRICKS),
    DECORATIVE("Декоративные предметы", Items.PAINTING), MECHANISMS("Механизмы", Items.REDSTONE),
    GEMS("Драгоценности", Items.DIAMOND), VEGETATION("Растительность", Items.OAK_SAPLING),
    MOB_DROPS("Лут с мобов", Items.ROTTEN_FLESH), MISC("Разное", Items.PAPER);

    private static final Map<Item, List<AuctionCategory>> MATCH_CACHE =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private final String displayName;
    private final Item icon;
    AuctionCategory(String displayName, Item icon) { this.displayName = displayName; this.icon = icon; }
    public String displayName() {
        String value = definition(); int pipe = value.indexOf('|');
        return pipe < 0 ? displayName : value.substring(0, pipe).trim();
    }
    public Item icon() {
        String value = definition(); int pipe = value.indexOf('|');
        ResourceLocation id = pipe < 0 ? null : ResourceLocation.tryParse(value.substring(pipe + 1).trim());
        Item configured = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        return configured == null || configured == Items.AIR ? icon : configured;
    }
    public String iconId() { return String.valueOf(ForgeRegistries.ITEMS.getKey(icon())); }
    public boolean accepts(AuctionListing listing) { return this == ALL || matching(listing.item()).contains(this); }
    public AuctionCategory next(int direction) {
        AuctionCategory[] values = values();
        return values[Math.floorMod(ordinal() + direction, values.length)];
    }
    private String definition() {
        String prefix = name() + "=";
        for (String raw : AuctionConfig.CATEGORY_DEFINITIONS.get())
            if (raw.regionMatches(true, 0, prefix, 0, prefix.length())) return raw.substring(prefix.length());
        return displayName + "|" + ForgeRegistries.ITEMS.getKey(icon);
    }

    public static AuctionCategory classify(ItemStack stack) {
        AuctionCategory override = configuredOverride(stack);
        if (override != null && override != ALL) return override;
        Item item = stack.getItem();
        if (item instanceof ArmorItem || stack.is(Items.ELYTRA)) return ARMOR;
        if (item instanceof SwordItem || item instanceof BowItem || item instanceof CrossbowItem
                || item instanceof TridentItem || item instanceof ShieldItem) return WEAPONS;
        if (item instanceof DiggerItem || item instanceof ShearsItem || item instanceof FishingRodItem
                || item instanceof FlintAndSteelItem || item instanceof BrushItem) return TOOLS;
        if (stack.isEdible()) return FOOD;
        if (item instanceof PotionItem) return POTIONS;
        if (item instanceof EnchantedBookItem || stack.is(Items.BOOK) || stack.is(Items.EXPERIENCE_BOTTLE)) return ENCHANTING;
        if (stack.is(Items.BLAZE_POWDER) || stack.is(Items.GHAST_TEAR) || stack.is(Items.NETHER_WART)
                || stack.is(Items.FERMENTED_SPIDER_EYE) || stack.is(Items.GLISTERING_MELON_SLICE)) return ALCHEMY;
        if (stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.DRAGON_EGG) || stack.is(Items.NETHER_STAR)
                || stack.is(Items.HEART_OF_THE_SEA) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) return UNIQUE;
        if (isTag(stack, "forge:gems") || isTag(stack, "forge:ingots") || isTag(stack, "forge:ores")
                || isTag(stack, "forge:raw_materials") || isTag(stack, "forge:dusts")) return GEMS;
        if (isTag(stack, "minecraft:saplings") || isTag(stack, "minecraft:flowers") || isTag(stack, "forge:seeds")
                || stack.is(Items.WHEAT) || stack.is(Items.SUGAR_CANE) || stack.is(Items.CACTUS)) return VEGETATION;
        if (isMobDrop(stack)) return MOB_DROPS;
        if (isMechanism(stack)) return MECHANISMS;
        if (item instanceof BlockItem block) {
            Block b = block.getBlock();
            if (b instanceof FlowerBlock || b instanceof LeavesBlock || b instanceof SaplingBlock) return VEGETATION;
            if (b instanceof CarpetBlock || b instanceof BannerBlock || b instanceof SkullBlock
                    || b instanceof BedBlock || b instanceof GlassBlock || b instanceof StainedGlassPaneBlock) return DECORATIVE;
            return BLOCKS;
        }
        return MISC;
    }

    /** Supports the same item appearing in several human categories via repeated categoryOverrides entries. */
    public static List<AuctionCategory> matching(ItemStack stack) {
        return MATCH_CACHE.computeIfAbsent(stack.getItem(), ignored -> {
            List<AuctionCategory> configured = configuredMatches(stack);
            return configured.isEmpty() ? List.of(classify(stack)) : List.copyOf(configured);
        });
    }

    public static void clearCache() {
        MATCH_CACHE.clear();
    }

    private static boolean isMobDrop(ItemStack s) {
        return s.is(Items.ROTTEN_FLESH) || s.is(Items.BONE) || s.is(Items.STRING) || s.is(Items.GUNPOWDER)
                || s.is(Items.SPIDER_EYE) || s.is(Items.ENDER_PEARL) || s.is(Items.SLIME_BALL)
                || s.is(Items.PHANTOM_MEMBRANE) || s.is(Items.PRISMARINE_SHARD) || s.is(Items.SHULKER_SHELL);
    }
    private static boolean isMechanism(ItemStack s) {
        if (s.getItem() instanceof BlockItem b && b.getBlock() instanceof EntityBlock) return true;
        return s.is(Items.REDSTONE) || s.is(Items.REPEATER) || s.is(Items.COMPARATOR) || s.is(Items.PISTON)
                || s.is(Items.STICKY_PISTON) || s.is(Items.OBSERVER) || s.is(Items.HOPPER)
                || s.is(Items.DISPENSER) || s.is(Items.DROPPER);
    }
    private static boolean isTag(ItemStack stack, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key != null && stack.is(TagKey.create(Registries.ITEM, key));
    }
    private static AuctionCategory configuredOverride(ItemStack stack) {
        List<AuctionCategory> matches = configuredMatches(stack);
        return matches.isEmpty() ? null : matches.get(0);
    }
    private static List<AuctionCategory> configuredMatches(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return List.of();
        LinkedHashSet<AuctionCategory> result = new LinkedHashSet<>();
        for (String raw : AuctionConfig.CATEGORY_OVERRIDES.get()) {
            int split = raw.lastIndexOf('='); if (split <= 0) continue;
            AuctionCategory category;
            try { category = valueOf(raw.substring(split + 1).trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) { continue; }
            String selector = raw.substring(0, split).trim();
            boolean matches = selector.startsWith("#") ? isTag(stack, selector.substring(1))
                    : selector.endsWith(":*") ? itemId.getNamespace().equalsIgnoreCase(selector.substring(0, selector.length() - 2))
                    : itemId.toString().equalsIgnoreCase(selector);
            if (matches && category != ALL) result.add(category);
        }
        return List.copyOf(result);
    }
}

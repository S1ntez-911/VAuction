package com.valorcraft.vauction.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

import java.util.Locale;
import java.util.List;
import java.util.Set;

/** Classifies one real server ItemStack. Unknown items intentionally remain OTHER. */
public final class MarketCategoryClassifier {
    public record Result(MarketCategory category, String reason, List<String> tags) {}
    private static final Set<String> RESOURCE_WORDS = Set.of(
            "ore", "raw_material", "ingot", "nugget", "dust", "plate", "foil", "rod", "wire",
            "gear", "gem", "crystal", "bolt", "screw", "ring", "spring", "rotor", "lens",
            "ores", "raw_materials", "ingots", "nuggets", "dusts", "plates", "foils", "rods",
            "wires", "gears", "gems", "crystals", "bolts", "screws", "rings", "springs",
            "rotors", "lenses");
    private static final Set<String> TOOL_TAG_WORDS = Set.of(
            "tools", "pickaxes", "axes", "shovels", "hoes", "swords", "knives", "hammers",
            "saws", "wrenches", "files", "mortars", "wire_cutters", "crowbars");
    private static final Set<String> MACHINE_WORDS = Set.of(
            "machine", "machine_hull", "casing", "controller", "motor", "pump", "conveyor",
            "robot_arm", "emitter", "sensor", "field_generator", "circuit", "transformer");

    private MarketCategoryClassifier() {}

    public static MarketCategory classify(ItemStack stack) {
        return diagnose(stack).category();
    }

    public static Result diagnose(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new Result(MarketCategory.OTHER, "empty item", List.of());
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = key == null ? "" : key.toString();
        List<String> tags = stack.getTags().map(TagKey::location).map(ResourceLocation::toString).sorted().toList();
        MarketCategory override = MarketCategoryConfig.override(id);
        if (override != null) return new Result(override, "item override: " + id, tags);
        for (String tag : tags) {
            MarketCategoryConfig.TagRule rule = MarketCategoryConfig.tagOverride(tag);
            if (rule != null) return new Result(rule.category(), "tag override: " + rule.glob() + " <- " + tag, tags);
        }
        if (stack.isEdible()) return new Result(MarketCategory.FOOD, "item is edible", tags);
        Item item = stack.getItem();
        if (item instanceof DiggerItem || item instanceof SwordItem || item instanceof ShearsItem
                || item instanceof FishingRodItem || item instanceof TridentItem
                || item instanceof ProjectileWeaponItem || item instanceof ShieldItem
                || item instanceof ArmorItem || hasTagWord(stack, TOOL_TAG_WORDS)) {
            return new Result(MarketCategory.TOOLS, "tool/equipment type or tag", tags);
        }
        String path = key == null ? "" : key.getPath().toLowerCase(Locale.ROOT);
        if (hasTagWord(stack, RESOURCE_WORDS) || containsWord(path, RESOURCE_WORDS)) {
            return new Result(MarketCategory.RESOURCES, "resource tag or registry id", tags);
        }
        if (item instanceof BlockItem && containsWord(path, MACHINE_WORDS)) {
            return new Result(MarketCategory.MACHINES, "machine block registry id", tags);
        }
        if (containsWord(path, MACHINE_WORDS)) {
            return new Result(MarketCategory.MACHINES, "machine component registry id", tags);
        }
        return new Result(MarketCategory.OTHER, "no matching rule", tags);
    }

    private static boolean hasTagWord(ItemStack stack, Set<String> words) {
        return stack.getTags().map(TagKey::location).map(ResourceLocation::getPath)
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> containsWord(value, words));
    }

    private static boolean containsWord(String value, Set<String> words) {
        for (String word : words) {
            if (value.equals(word) || value.startsWith(word + "/") || value.endsWith("/" + word)
                    || value.contains("_" + word) || value.contains(word + "_")
                    || value.contains("/" + word + "/")) return true;
        }
        return false;
    }
}

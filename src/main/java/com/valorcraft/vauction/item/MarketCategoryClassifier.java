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
import java.util.Set;

/** Classifies one real server ItemStack. Unknown items intentionally remain OTHER. */
public final class MarketCategoryClassifier {
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
        if (stack == null || stack.isEmpty()) return MarketCategory.OTHER;
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = key == null ? "" : key.toString();
        MarketCategory override = MarketCategoryConfig.override(id);
        if (override != null) return override;
        if (stack.isEdible()) return MarketCategory.FOOD;
        Item item = stack.getItem();
        if (item instanceof DiggerItem || item instanceof SwordItem || item instanceof ShearsItem
                || item instanceof FishingRodItem || item instanceof TridentItem
                || item instanceof ProjectileWeaponItem || item instanceof ShieldItem
                || item instanceof ArmorItem || hasTagWord(stack, TOOL_TAG_WORDS)) {
            return MarketCategory.TOOLS;
        }
        String path = key == null ? "" : key.getPath().toLowerCase(Locale.ROOT);
        if (hasTagWord(stack, RESOURCE_WORDS) || containsWord(path, RESOURCE_WORDS)) {
            return MarketCategory.RESOURCES;
        }
        if (item instanceof BlockItem && containsWord(path, MACHINE_WORDS)) return MarketCategory.MACHINES;
        if (containsWord(path, MACHINE_WORDS)) return MarketCategory.MACHINES;
        return MarketCategory.OTHER;
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

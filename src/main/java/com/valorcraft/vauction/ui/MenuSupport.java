package com.valorcraft.vauction.ui;

import com.valorcraft.vauction.config.AuctionConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;

final class MenuSupport {
    private MenuSupport() {}
    static void slots(java.util.function.Consumer<Slot> add, Container display, Inventory inventory, int rows) {
        for (int row = 0; row < rows; row++) for (int col = 0; col < 9; col++)
            add.accept(new LockedSlot(display, col + row * 9, 8 + col * 18, 18 + row * 18));
        int y = rows * 18 + 32;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            add.accept(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, y + row * 18));
        for (int col = 0; col < 9; col++) add.accept(new Slot(inventory, col, 8 + col * 18, y + 58));
    }
    static ItemStack icon(Item item, String name, ChatFormatting color, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(Component.literal(name).withStyle(color).withStyle(style -> style.withItalic(false)));
        Component[] lines = new Component[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = Component.literal(lore[i]).withStyle(ChatFormatting.GRAY);
        lore(stack, lines); return stack;
    }
    static ItemStack icon(Item item, Component name, Component... lore) {
        ItemStack stack = new ItemStack(item); stack.setHoverName(name.copy().withStyle(style -> style.withItalic(false)));
        lore(stack, lore); return stack;
    }
    static Item configured(ForgeConfigSpec.ConfigValue<String> value, Item fallback) {
        ResourceLocation id = ResourceLocation.tryParse(value.get());
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        return item == null || item == Items.AIR ? fallback : item;
    }
    static Component[] lines(String value) {
        return java.util.Arrays.stream(value.split("\\n", -1))
                .map(com.valorcraft.vauction.lang.AuctionLang::legacy).toArray(Component[]::new);
    }
    static java.util.List<Component> categoryLines(ItemStack stack) {
        java.util.List<AuctionCategory> categories = AuctionCategory.matching(stack);
        java.util.ArrayList<Component> result = new java.util.ArrayList<>();
        if (categories.isEmpty()) return result;
        result.add(com.valorcraft.vauction.lang.AuctionLang.component("tm2.lot.category", "category", categories.get(0).displayName()));
        for (int i = 1; i < categories.size(); i += 2) {
            String names = categories.get(i).displayName();
            if (i + 1 < categories.size()) names += ", " + categories.get(i + 1).displayName();
            result.add(com.valorcraft.vauction.lang.AuctionLang.component("tm2.lot.category_cont", "categories", names));
        }
        return result;
    }
    static void lore(ItemStack stack, Component... lines) {
        CompoundTag tag = stack.getOrCreateTagElement("display");
        ListTag lore = tag.contains("Lore", Tag.TAG_LIST) ? tag.getList("Lore", Tag.TAG_STRING) : new ListTag();
        for (Component line : lines) lore.add(StringTag.valueOf(Component.Serializer.toJson(
                line.copy().withStyle(style -> style.withItalic(false)))));
        tag.put("Lore", lore);
    }
    private static final class LockedSlot extends Slot {
        LockedSlot(Container c, int i, int x, int y) { super(c, i, x, y); }
        @Override public boolean mayPickup(Player player) { return false; }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
    }
}

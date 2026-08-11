package com.valorcraft.vauction.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

import java.util.List;

final class GuiItems {
    private GuiItems() {}

    static ItemStack namedButton(ItemStack source, String name, ChatFormatting color, String... lore) {
        ItemStack result = source.copy();
        if (result.isEmpty()) return result;
        result.setCount(1);
        result.setHoverName(Component.literal(name).withStyle(color));
        setLore(result, List.of(lore));
        return result;
    }

    static ItemStack namedButton(ItemStack source, Component name, List<Component> lore) {
        ItemStack result = source.copy();
        if (result.isEmpty()) return result;
        result.setCount(1);
        result.setHoverName(name);
        setComponentLore(result, lore);
        return result;
    }

    static ItemStack named(ItemStack source, String name, ChatFormatting color, String... lore) {
        return namedButton(source, name, color, lore);
    }

    /** Adds exchange information without replacing the item's native name, lore or NBT. */
    static ItemStack decorateMarketItem(ItemStack source, List<Component> marketLines) {
        ItemStack result = source.copy();
        if (result.isEmpty()) return result;
        result.setCount(1);
        CompoundTag display = result.getOrCreateTagElement("display");
        ListTag lore = display.contains("Lore", net.minecraft.nbt.Tag.TAG_LIST)
                ? display.getList("Lore", net.minecraft.nbt.Tag.TAG_STRING).copy()
                : new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.empty())));
        for (Component line : marketLines) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(line)));
        }
        lore.add(StringTag.valueOf(Component.Serializer.toJson(MarketText.divider())));
        display.put("Lore", lore);
        return result;
    }

    /**
     * Server-only safe market card. TFG/GT clients append large chemical formulae to
     * real material stacks after the server has built their lore; a neutral vanilla
     * icon is therefore the only reliable way to keep the auction tooltip readable.
     * The real stack remains in GuiAction/MarketSession and is never replaced in the
     * order identity or delivery path.
     */
    static ItemStack marketDisplay(ItemStack realItem, Item safeIcon, List<Component> marketLines) {
        Component name = realItem == null || realItem.isEmpty()
                ? Component.literal("Неизвестный предмет") : realItem.getHoverName().copy();
        return namedButton(new ItemStack(safeIcon), name, marketLines);
    }

    static void setLore(ItemStack stack, List<String> lines) {
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        for (String line : lines) {
            Component component = Component.literal(line).withStyle(ChatFormatting.GRAY);
            lore.add(StringTag.valueOf(Component.Serializer.toJson(component)));
        }
        display.put("Lore", lore);
    }

    private static void setComponentLore(ItemStack stack, List<Component> lines) {
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        for (Component line : lines) lore.add(StringTag.valueOf(Component.Serializer.toJson(line)));
        display.put("Lore", lore);
    }
}

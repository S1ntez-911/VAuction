package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.item.ExactItemMarketKeyStrategy;
import com.valorcraft.vauction.item.ItemStackCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiItemsTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try { Bootstrap.bootStrap(); } catch (Throwable ignored) {}
    }

    @Test
    void marketDecorationPreservesNativeNameLoreEnchantDamageAndNbt() {
        ItemStack source = new ItemStack(Items.DIAMOND_PICKAXE, 17);
        source.setHoverName(Component.literal("Закалённый медный слиток").withStyle(ChatFormatting.AQUA));
        source.setDamageValue(42);
        source.enchant(Enchantments.UNBREAKING, 3);
        source.getOrCreateTag().putInt("Energy", 9001);
        ListTag originalLore = new ListTag();
        originalLore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("Качество: высокое"))));
        source.getOrCreateTagElement("display").put("Lore", originalLore);
        CompoundTag before = source.save(new CompoundTag());

        ItemStack decorated = GuiItems.decorateMarketItem(source, List.of(
                Component.literal("Купить: 32"), Component.literal("ЛКМ — открыть")));

        assertNotSame(source, decorated);
        assertEquals(1, decorated.getCount());
        assertEquals("Закалённый медный слиток", decorated.getHoverName().getString());
        assertEquals(42, decorated.getDamageValue());
        assertEquals(9001, decorated.getTag().getInt("Energy"));
        assertEquals(before, source.save(new CompoundTag()), "decorator must not mutate the clean source");
        ListTag lore = decorated.getTagElement("display").getList("Lore", CompoundTag.TAG_STRING);
        assertTrue(lore.getString(0).contains("Качество: высокое"));
        assertTrue(lore.getString(lore.size() - 2).contains("Купить: 32"));
        assertTrue(lore.getString(lore.size() - 1).contains("ЛКМ — открыть"));
    }

    @Test
    void cleanStoredUnitKeepsExactMarketIdentity() throws Exception {
        ItemStack original = new ItemStack(Items.POTION, 32);
        original.getOrCreateTag().putString("TFGGrade", "high");
        ItemStack cleanStoredUnit = original.copy();
        cleanStoredUnit.setCount(1);
        ExactItemMarketKeyStrategy keys = new ExactItemMarketKeyStrategy(
                new ItemStackCodec(262_144, 2_097_152));
        assertEquals(keys.keyOf(original), keys.keyOf(cleanStoredUnit));
    }
}

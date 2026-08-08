package com.valorcraft.vauction.item;

import net.minecraft.SharedConstants;
import net.minecraft.DetectedVersion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.registries.ForgeRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полный цикл предмета через сериализацию: ItemStack в БД-снимок и обратно.
 * <p>
 * Реальная кодировка: serializeNBT → несжатые NBT-байты → SHA-256 → gzip в blob.
 * Изменение любой части (count, damage, name, enchant, NBT) должно менять hash.
 * Повреждённый blob/неизвестный кодек/недопустимые размеры — ошибки.
 */
class ItemStackCodecTest {

    private static final ItemStackCodec CODEC = new ItemStackCodec(262_144, 2_097_152);

    /**
     * Инициализация ванильных регистров (Items). Без этого нельзя создать
     * осмысленный ItemStack вне полноценного игрового окружения.
     */
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // JUnit-окружение: Forge-сеть не стартует (NetworkHooks.init),
            // регистры Items уже подняты — этого достаточно для round-trip тестов.
        }
    }

    @Test
    void roundTripKeepsItemIdentity() throws ItemCodecException {
        ItemSnapshot snapshot = CODEC.encode(new ItemStack(Items.DIAMOND, 1));
        ItemStack decoded = CODEC.decode(snapshot);
        assertEquals("minecraft:diamond", ForgeRegistries.ITEMS.getKey(decoded.getItem()).toString());
        assertEquals(1, decoded.getCount());
        assertEquals("minecraft:diamond", snapshot.registryId());
        assertEquals(1, snapshot.quantity());
        assertFalse(snapshot.hash().isBlank());
        assertFalse(snapshot.searchName().isBlank());
        assertEquals("diamond | minecraft:diamond", snapshot.searchName());
    }

    @Test
    void roundTripPreservesDamageNameAndEnchant() throws ItemCodecException {
        ItemStack stack = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        stack.setDamageValue(42);
        stack.setHoverName(Component.literal("Тестовое имя"));
        stack.enchant(Enchantments.UNBREAKING, 3);

        ItemStack decoded = CODEC.decode(CODEC.encode(stack));
        assertEquals(42, decoded.getDamageValue());
        assertEquals("Тестовое имя", decoded.getHoverName().getString());
        assertTrue(decoded.hasTag());
        assertEquals(1, decoded.getTag().getList("Enchantments", CompoundTag.TAG_COMPOUND).size(),
                "список зачарований должен пережить round-trip");
    }

    @Test
    void hashIsStableForSameContent() throws ItemCodecException {
        ItemStack a = new ItemStack(Items.DIAMOND_SWORD, 1);
        ItemStack b = new ItemStack(Items.DIAMOND_SWORD, 1);
        a.setHoverName(Component.literal("Клинок"));
        b.setHoverName(Component.literal("Клинок"));
        assertEquals(CODEC.encode(a).hash(), CODEC.encode(b).hash());
    }

    @Test
    void hashChangesWhenContentDiffers() throws ItemCodecException {
        assertNotEquals(CODEC.encode(new ItemStack(Items.DIAMOND, 1)).hash(),
                CODEC.encode(new ItemStack(Items.DIAMOND, 2)).hash());
    }

    @Test
    void unknownCodecVersionFails() throws ItemCodecException {
        ItemSnapshot snapshot = CODEC.encode(new ItemStack(Items.DIAMOND));
        ItemSnapshot foreign = new ItemSnapshot(snapshot.serializedData(), "foreign_codec_v9",
                snapshot.hash(), snapshot.registryId(), snapshot.displayName(), snapshot.searchName(),
                snapshot.quantity());
        ItemCodecException ex = assertThrows(ItemCodecException.class, () -> CODEC.decode(foreign));
        assertEquals(ItemCodecError.UNSUPPORTED_CODEC, ex.error());
    }

    @Test
    void corruptedBlobFails() throws ItemCodecException {
        ItemSnapshot snapshot = CODEC.encode(new ItemStack(Items.DIAMOND));
        byte[] corrupted = Arrays.copyOf(snapshot.serializedData(), snapshot.serializedData().length);
        corrupted[0] ^= 0x5A;
        ItemCodecException ex = assertThrows(ItemCodecException.class,
                () -> CODEC.decode(new ItemSnapshot(corrupted, snapshot.codecVersion(), snapshot.hash(),
                        snapshot.registryId(), snapshot.displayName(), snapshot.searchName(), snapshot.quantity())));
        assertEquals(ItemCodecError.CORRUPTED_ITEM_DATA, ex.error());
    }

    @Test
    void tamperedHashFails() throws ItemCodecException {
        ItemSnapshot snapshot = CODEC.encode(new ItemStack(Items.DIAMOND));
        ItemCodecException ex = assertThrows(ItemCodecException.class,
                () -> CODEC.decode(new ItemSnapshot(snapshot.serializedData(), snapshot.codecVersion(),
                        "0".repeat(64), snapshot.registryId(), snapshot.displayName(), snapshot.searchName(),
                        snapshot.quantity())));
        assertEquals(ItemCodecError.CORRUPTED_ITEM_DATA, ex.error());
    }

    @Test
    void emptyStackIsRejected() {
        ItemCodecException ex = assertThrows(ItemCodecException.class, () -> CODEC.encode(ItemStack.EMPTY));
        assertEquals(ItemCodecError.EMPTY_ITEM, ex.error());
    }

    @Test
    void itemTooLargeIsRejected() {
        ItemStackCodec tiny = new ItemStackCodec(8, 8);
        ItemCodecException ex = assertThrows(ItemCodecException.class,
                () -> tiny.encode(new ItemStack(Items.DIAMOND)));
        assertEquals(ItemCodecError.ITEM_TOO_LARGE, ex.error());
    }

    @Test
    void tooLargeDecodeFails() throws ItemCodecException {
        ItemSnapshot snapshot = CODEC.encode(new ItemStack(Items.DIAMOND));
        ItemCodecException ex = assertThrows(ItemCodecException.class,
                () -> new ItemStackCodec(8, 8).decode(snapshot));
        assertEquals(ItemCodecError.ITEM_TOO_LARGE, ex.error());
    }

    @Test
    void normalizeSearchNameTrimsAndLowercases() {
        assertEquals("diamond | minecraft:diamond",
                ItemStackCodec.normalizeSearchName(" Diamond ", "minecraft:diamond"));
        assertEquals("ещё текст | minecraft:stone",
                ItemStackCodec.normalizeSearchName("ЕЩЁ ТЕКСТ", "minecraft:stone"));
    }
}
package com.valorcraft.vauction.item;

import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoredContentsTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try { Bootstrap.bootStrap(); } catch (Throwable ignored) {}
    }

    @Test
    void readsVanillaContainerSlotsAndPreservesEmptyPositions() {
        ItemStack shulker = shulkerWithDiamond(3, 5);

        StoredContents.Inspection contents = StoredContents.inspect(shulker);

        assertTrue(contents.readable(), contents.error());
        assertTrue(contents.hasItems());
        assertEquals(27, contents.slots().size());
        assertEquals(Items.DIAMOND, contents.slots().get(3).getItem());
        assertEquals(5, contents.slots().get(3).getCount());
        assertEquals(1, contents.occupiedItemSlots());
        assertEquals(5, contents.itemCount());
    }

    @Test
    void codecRoundTripKeepsContainerContentsExactly() throws Exception {
        ItemStack original = shulkerWithDiamond(7, 12);
        ItemStackCodec codec = new ItemStackCodec(262_144, 2_097_152);

        StoredContents.Inspection before = StoredContents.inspect(original);
        StoredContents.Inspection after = StoredContents.inspect(codec.decode(codec.encode(original)));

        assertTrue(StoredContents.same(before, after));
        after.slots().get(7).setCount(11);
        assertFalse(StoredContents.same(before, after));
    }

    private static ItemStack shulkerWithDiamond(int slot, int count) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        CompoundTag entry = new ItemStack(Items.DIAMOND, count).save(new CompoundTag());
        entry.putByte("Slot", (byte) slot);
        ListTag items = new ListTag();
        items.add(entry);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Items", items);
        shulker.getOrCreateTag().put("BlockEntityTag", blockEntity);
        return shulker;
    }
}

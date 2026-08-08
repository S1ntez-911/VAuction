package com.valorcraft.vauction.item;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.config.ItemPolicyMode;
import net.minecraft.SharedConstants;
import net.minecraft.DetectedVersion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPolicyTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // JUnit-окружение: Forge-сеть не стартует (NetworkHooks.init),
            // регистры Items уже подняты — этого достаточно для тестов политики.
        }
    }

    private static AuctionSettings settings(ItemPolicyMode mode, List<String> blockedItems,
                                         List<String> blockedTags, List<String> whitelistedItems,
                                         List<String> whitelistedTags, boolean allowContainers) {
        AuctionSettings d = AuctionSettings.defaults();
        return new AuctionSettings(
                d.enabled(), d.listingDurationHours(), d.maxActiveListingsPerPlayer(),
                d.maxBuyOrdersPerPlayer(), d.listingFeeMinor(), d.commissionBps(),
                d.expiredRetentionDays(), d.historyRetentionDays(), d.allowSelfPurchase(),
                allowContainers, false, d.allowEnchantedBooks(),
                d.maxCompressedItemBytes(), d.maxUncompressedItemBytes(),
                d.sellOrderExpiryDays(), d.buyOrderExpiryDays(),
                mode, blockedItems, blockedTags, whitelistedItems, whitelistedTags);
    }

    @Test
    void emptyOrNullStackIsRejected() {
        assertEquals(ItemPolicy.Failure.EMPTY_ITEM,
                ItemPolicy.check(null, AuctionSettings.defaults()).failure());
        assertEquals(ItemPolicy.Failure.EMPTY_ITEM,
                ItemPolicy.check(ItemStack.EMPTY, AuctionSettings.defaults()).failure());
    }

    @Test
    void overstackedCopyIsRejected() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 128);
        assertEquals(ItemPolicy.Failure.COUNT_OVERSTACK,
                ItemPolicy.check(stack, AuctionSettings.defaults()).failure());
    }

    @Test
    void blacklistBlocksItemAndTag() {
        AuctionSettings blacklist = settings(ItemPolicyMode.BLACKLIST,
                List.of("minecraft:diamond"), List.of(), List.of(), List.of(), false);
        assertEquals(ItemPolicy.Failure.ITEM_BLOCKED,
                ItemPolicy.check(new ItemStack(Items.DIAMOND), blacklist).failure());
        assertTrue(ItemPolicy.check(new ItemStack(Items.DIRT), blacklist).allowed());

        AuctionSettings tagBlocked = settings(ItemPolicyMode.BLACKLIST,
                List.of(), List.of("minecraft:diamond_ores"), List.of(), List.of(), false);
        assertTrue(ItemPolicy.check(new ItemStack(Items.DIAMOND), tagBlocked).allowed());
    }

    @Test
    void whitelistAllowsOnlyListedItems() {
        AuctionSettings whitelist = settings(ItemPolicyMode.WHITELIST,
                List.of(), List.of(), List.of("minecraft:dirt"), List.of(), false);
        assertTrue(ItemPolicy.check(new ItemStack(Items.DIRT), whitelist).allowed());
        assertEquals(ItemPolicy.Failure.ITEM_NOT_ALLOWED,
                ItemPolicy.check(new ItemStack(Items.DIAMOND), whitelist).failure());
    }

    @Test
    void containerWithContentsIsBlockedByDefault() {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        CompoundTag items = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag cell = new CompoundTag();
        cell.putString("id", "minecraft:diamond");
        cell.putByte("Count", (byte) 1);
        list.add(cell);
        items.put("Items", list);
        shulker.getOrCreateTag().put("BlockEntityTag", items);

        AuctionSettings strict = settings(ItemPolicyMode.BLACKLIST,
                List.of(), List.of(), List.of(), List.of(), false);
        assertEquals(ItemPolicy.Failure.CONTAINER_WITH_CONTENTS,
                ItemPolicy.check(shulker, strict).failure());

        AuctionSettings lenient = settings(ItemPolicyMode.BLACKLIST,
                List.of(), List.of(), List.of(), List.of(), true);
        assertTrue(ItemPolicy.check(shulker, lenient).allowed());
    }
}
package com.valorcraft.vauction.item;

import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketCategoryClassifierTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge networking is not started by this unit test; vanilla item registries are enough.
        }
    }

    @Test
    void classifiesVanillaFoodToolsResourcesAndUnknownsFromRealStacks() {
        assertEquals(MarketCategory.FOOD, MarketCategoryClassifier.classify(new ItemStack(Items.BREAD)));
        assertEquals(MarketCategory.TOOLS, MarketCategoryClassifier.classify(new ItemStack(Items.IRON_PICKAXE)));
        assertEquals(MarketCategory.RESOURCES, MarketCategoryClassifier.classify(new ItemStack(Items.IRON_INGOT)));
        assertEquals(MarketCategory.OTHER, MarketCategoryClassifier.classify(new ItemStack(Items.TORCH)));
    }
}

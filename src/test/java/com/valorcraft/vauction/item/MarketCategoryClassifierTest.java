package com.valorcraft.vauction.item;

import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketCategoryClassifierTest {
    @TempDir
    Path tempDir;
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge networking is not started by this unit test; vanilla item registries are enough.
        }
    }

    @BeforeEach
    void resetCategoryRules() {
        MarketCategoryConfig.start(tempDir);
    }

    @Test
    void classifiesVanillaFoodToolsResourcesAndUnknownsFromRealStacks() {
        assertEquals(MarketCategory.FOOD, MarketCategoryClassifier.classify(new ItemStack(Items.BREAD)));
        assertEquals(MarketCategory.TOOLS, MarketCategoryClassifier.classify(new ItemStack(Items.IRON_PICKAXE)));
        assertEquals(MarketCategory.RESOURCES, MarketCategoryClassifier.classify(new ItemStack(Items.IRON_INGOT)));
        assertEquals(MarketCategory.OTHER, MarketCategoryClassifier.classify(new ItemStack(Items.TORCH)));
    }

    @Test
    void reloadAppliesItemOverridesAndKeepsLastGoodRulesAfterInvalidEdit() throws Exception {
        Path file = tempDir.resolve("VMods").resolve("VAuction").resolve("vauction-categories.json");
        Files.writeString(file, "{\"overrides\":{\"minecraft:torch\":\"tools\"},"
                + "\"tagOverrides\":{\"forge:foods/*\":\"food\"}}");
        assertEquals(null, MarketCategoryConfig.reload());
        assertEquals(MarketCategory.TOOLS, MarketCategoryClassifier.classify(new ItemStack(Items.TORCH)));
        assertEquals(MarketCategory.FOOD, MarketCategoryConfig.tagOverride("forge:foods").category());
        assertEquals(MarketCategory.FOOD, MarketCategoryConfig.tagOverride("forge:foods/fruits").category());

        Files.writeString(file, "{\"overrides\":{\"minecraft:torch\":\"unknown\"}}");
        org.junit.jupiter.api.Assertions.assertTrue(MarketCategoryConfig.reload().contains("Unknown category"));
        assertEquals(MarketCategory.TOOLS, MarketCategoryClassifier.classify(new ItemStack(Items.TORCH)));
    }
}

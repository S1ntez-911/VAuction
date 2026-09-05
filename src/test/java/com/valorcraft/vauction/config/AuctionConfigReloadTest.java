package com.valorcraft.vauction.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuctionConfigReloadTest {
    private CommentedConfig defaults() {
        CommentedConfig config = CommentedConfig.inMemory();
        AuctionConfig.SPEC.correct(config);
        return config;
    }

    @Test void acceptsChangedIconsAndComments() {
        var config = defaults();
        config.set("interface.refreshItem", "minecraft:diamond");
        config.setComment("interface", "Custom comment");
        assertDoesNotThrow(() -> AuctionConfig.validateReload(config));
        assertEquals("minecraft:diamond", config.get("interface.refreshItem"));
    }

    @Test void rejectsOutOfRangeValues() {
        var config = defaults();
        config.set("auction.maxListingsPerPlayer", 0);
        assertThrows(IllegalArgumentException.class, () -> AuctionConfig.validateReload(config));
    }

    @Test void rejectsMissingSettings() {
        var config = defaults();
        config.remove("interface.refreshItem");
        assertThrows(IllegalArgumentException.class, () -> AuctionConfig.validateReload(config));
    }

    @Test void rejectsWrongTypes() {
        var config = defaults();
        config.set("interface.refreshItem", 123);
        assertThrows(IllegalArgumentException.class, () -> AuctionConfig.validateReload(config));
    }
}

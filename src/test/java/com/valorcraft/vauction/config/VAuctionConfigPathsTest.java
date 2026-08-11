package com.valorcraft.vauction.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VAuctionConfigPathsTest {
    @TempDir
    Path temp;

    @Test
    void everyConfigUsesNamespacedDirectoryAndLegacyFileMovesOnce() throws Exception {
        Path legacy = temp.resolve("vauction-server.toml");
        Files.writeString(legacy, "enabled=true");

        Path target = VAuctionConfigPaths.file(temp, "vauction-server.toml");

        assertEquals(temp.resolve("VMods").resolve("VAuction").resolve("vauction-server.toml"), target);
        assertEquals("enabled=true", Files.readString(target));
        assertFalse(Files.exists(legacy));
        assertEquals("VMods/VAuction/vauction-server.toml",
                VAuctionConfigPaths.forgeFileName("vauction-server.toml"));
    }

    @Test
    void existingNamespacedConfigIsNeverOverwrittenByLegacyFile() throws Exception {
        Path target = VAuctionConfigPaths.file(temp, "vauction-ui.json");
        Files.writeString(target, "new");
        Path legacy = temp.resolve("vauction-ui.json");
        Files.writeString(legacy, "old");

        assertEquals(target, VAuctionConfigPaths.file(temp, "vauction-ui.json"));
        assertEquals("new", Files.readString(target));
        assertTrue(Files.exists(legacy));
    }

    @Test
    void formerWorldServerConfigMovesFromConfiguredLevelBeforeForgeLoadsCommonConfig() throws Exception {
        Path game = temp.resolve("server");
        Path config = game.resolve("config");
        Files.createDirectories(game);
        Files.writeString(game.resolve("server.properties"), "level-name=valor_world\n");
        Path legacy = game.resolve("valor_world").resolve("serverconfig").resolve("vauction-server.toml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "commission_percent=1.5");

        Path target = VAuctionConfigPaths.migrateLegacyWorldFile(config, game, "vauction-server.toml");

        assertEquals(config.resolve("VMods").resolve("VAuction").resolve("vauction-server.toml"), target);
        assertEquals("commission_percent=1.5", Files.readString(target));
        assertFalse(Files.exists(legacy));
    }
}

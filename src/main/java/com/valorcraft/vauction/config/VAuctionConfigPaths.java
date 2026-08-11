package com.valorcraft.vauction.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/** Shared, collision-free home for every VAuction configuration file. */
public final class VAuctionConfigPaths {
    public static final String FORGE_DIRECTORY = "VMods/VAuction";

    private VAuctionConfigPaths() {}

    public static Path directory(Path configRoot) throws IOException {
        Path directory = configRoot.resolve("VMods").resolve("VAuction");
        Files.createDirectories(directory);
        return directory;
    }

    /** Moves the former root-level file once and never overwrites a new-path file. */
    public static Path file(Path configRoot, String fileName) throws IOException {
        Path target = directory(configRoot).resolve(fileName);
        Path legacy = configRoot.resolve(fileName);
        if (!Files.exists(target) && Files.isRegularFile(legacy)) {
            Files.move(legacy, target);
        }
        return target;
    }

    public static String forgeFileName(String fileName) {
        return FORGE_DIRECTORY + "/" + fileName;
    }

    /**
     * Forge Type.SERVER used to keep this file in the active world's serverconfig.
     * Migrate the dedicated-server location before the new COMMON config is loaded.
     */
    public static Path migrateLegacyWorldFile(Path configRoot, Path gameDirectory,
                                              String fileName) throws IOException {
        Path target = directory(configRoot).resolve(fileName);
        if (Files.exists(target)) return target;

        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(gameRoot.resolve("world").resolve("serverconfig").resolve(fileName));

        Path propertiesFile = gameRoot.resolve("server.properties");
        if (Files.isRegularFile(propertiesFile)) {
            Properties properties = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(propertiesFile,
                    java.nio.charset.StandardCharsets.ISO_8859_1)) {
                properties.load(reader);
            }
            String levelName = properties.getProperty("level-name", "world").trim();
            if (!levelName.isEmpty()) {
                Path dedicated = gameRoot.resolve(levelName).normalize();
                Path integrated = gameRoot.resolve("saves").resolve(levelName).normalize();
                if (dedicated.startsWith(gameRoot)) {
                    candidates.add(dedicated.resolve("serverconfig").resolve(fileName));
                }
                if (integrated.startsWith(gameRoot)) {
                    candidates.add(integrated.resolve("serverconfig").resolve(fileName));
                }
            }
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                Files.move(candidate, target);
                break;
            }
        }
        return target;
    }
}

package com.valorcraft.vauction.item;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.valorcraft.vauction.config.VAuctionConfigPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small administrator override file; automatic rules remain the default. */
public final class MarketCategoryConfig {
    private static final Logger LOGGER = LogManager.getLogger("VAuction/Categories");
    private static volatile Map<String, MarketCategory> overrides = Map.of();

    private MarketCategoryConfig() {}

    public static void start(Path configRoot) {
        try {
            Path file = VAuctionConfigPaths.file(configRoot, "vauction-categories.json");
            if (!Files.exists(file)) {
                JsonObject root = new JsonObject();
                root.addProperty("format", 1);
                root.addProperty("help", "Необязательно. Укажите только исключения автоматической классификации: resources, food, tools, machines или other.");
                JsonObject examples = new JsonObject();
                examples.addProperty("minecraft:bread", "food");
                root.add("examples", examples);
                root.add("overrides", new JsonObject());
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                        .create().toJson(root), StandardCharsets.UTF_8);
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject root = parsed.getAsJsonObject();
            JsonObject configured = root.has("overrides") && root.get("overrides").isJsonObject()
                    ? root.getAsJsonObject("overrides") : new JsonObject();
            LinkedHashMap<String, MarketCategory> loaded = new LinkedHashMap<>();
            for (String id : configured.keySet()) {
                String category = configured.get(id).getAsString();
                MarketCategory value = MarketCategory.fromId(category);
                if (value == MarketCategory.OTHER && !"other".equalsIgnoreCase(category)) {
                    throw new IllegalArgumentException("Unknown category for " + id + ": " + category);
                }
                loaded.put(id.toLowerCase(java.util.Locale.ROOT), value);
            }
            overrides = Map.copyOf(loaded);
        } catch (Exception e) {
            overrides = Map.of();
            LOGGER.error("Cannot load market category overrides: {}", e.getMessage());
        }
    }

    static MarketCategory override(String registryId) {
        return registryId == null ? null : overrides.get(registryId.toLowerCase(java.util.Locale.ROOT));
    }
}

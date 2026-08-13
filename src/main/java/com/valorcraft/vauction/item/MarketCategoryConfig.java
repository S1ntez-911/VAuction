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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Small administrator override file; automatic rules remain the default. */
public final class MarketCategoryConfig {
    private static final Logger LOGGER = LogManager.getLogger("VAuction/Categories");
    private static volatile Map<String, MarketCategory> overrides = Map.of();
    private static volatile List<TagRule> tagOverrides = List.of();
    private static volatile Path file;

    record TagRule(String glob, Pattern pattern, MarketCategory category) {}

    private MarketCategoryConfig() {}

    public static void start(Path configRoot) {
        try {
            file = VAuctionConfigPaths.file(configRoot, "vauction-categories.json");
            if (!Files.exists(file)) {
                JsonObject root = new JsonObject();
                root.addProperty("format", 1);
                root.addProperty("help", "Необязательно. Укажите только исключения автоматической классификации: resources, food, tools, machines или other.");
                JsonObject examples = new JsonObject();
                examples.addProperty("minecraft:bread", "food");
                root.add("examples", examples);
                root.add("overrides", new JsonObject());
                JsonObject tagExamples = new JsonObject();
                tagExamples.addProperty("forge:foods/*", "food");
                tagExamples.addProperty("forge:tools/*", "tools");
                root.add("tagExamples", tagExamples);
                root.add("tagOverrides", new JsonObject());
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                        .create().toJson(root), StandardCharsets.UTF_8);
            }
            String error = reload();
            if (error != null) LOGGER.error("Cannot load market category overrides: {}", error);
        } catch (Exception e) {
            LOGGER.error("Cannot prepare market category overrides: {}", e.getMessage());
        }
    }

    /** Atomically replaces the active rules. A bad file keeps the last known-good snapshot. */
    public static String reload() {
        if (file == null) return "category config is not initialised";
        try {
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
                loaded.put(id.toLowerCase(Locale.ROOT), value);
            }
            JsonObject configuredTags = root.has("tagOverrides") && root.get("tagOverrides").isJsonObject()
                    ? root.getAsJsonObject("tagOverrides") : new JsonObject();
            java.util.ArrayList<TagRule> loadedTags = new java.util.ArrayList<>();
            for (String glob : configuredTags.keySet()) {
                String category = configuredTags.get(glob).getAsString();
                MarketCategory value = parseCategory(glob, category);
                String normalized = glob.toLowerCase(Locale.ROOT);
                if (!normalized.contains(":")) throw new IllegalArgumentException("Invalid tag pattern: " + glob);
                loadedTags.add(new TagRule(normalized, Pattern.compile(globRegex(normalized)), value));
            }
            overrides = Map.copyOf(loaded);
            tagOverrides = List.copyOf(loadedTags);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private static MarketCategory parseCategory(String source, String category) {
        MarketCategory value = MarketCategory.fromId(category);
        if (value == MarketCategory.OTHER && !"other".equalsIgnoreCase(category)) {
            throw new IllegalArgumentException("Unknown category for " + source + ": " + category);
        }
        return value;
    }

    private static String globRegex(String glob) {
        boolean rootAndChildren = glob.endsWith("/*");
        if (rootAndChildren) glob = glob.substring(0, glob.length() - 2);
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') out.append(".*");
            else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) out.append('\\').append(c);
            else out.append(c);
        }
        if (rootAndChildren) out.append("(?:/.*)?");
        return out.append('$').toString();
    }

    static MarketCategory override(String registryId) {
        return registryId == null ? null : overrides.get(registryId.toLowerCase(Locale.ROOT));
    }

    static TagRule tagOverride(String tagId) {
        if (tagId == null) return null;
        String normalized = tagId.toLowerCase(Locale.ROOT);
        for (TagRule rule : tagOverrides) if (rule.pattern().matcher(normalized).matches()) return rule;
        return null;
    }
}

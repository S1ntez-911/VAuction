package com.valorcraft.vauction.gui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.valorcraft.vauction.config.VAuctionConfigPaths;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One-file configuration for the only configurable screen: the auction catalogue. */
public final class UiConfig {
    private static final Logger LOGGER = LogManager.getLogger("VAuction/UI");
    private static final int FORMAT = 1;
    private static final Set<String> CONTROL_KEYS = Set.of(
            "empty", "previous", "categories", "refresh", "info", "my", "next");
    private static final Set<String> BUTTON_KEYS = Set.of(
            "previous", "next", "categories", "refresh", "my", "claim", "info", "empty");
    private static final Map<String, String> DEFAULT_TEXTS = Map.ofEntries(
            Map.entry("brand", "Аукцион"),
            Map.entry("filter.all", "Все"),
            Map.entry("filter.resources", "Ресурсы"),
            Map.entry("filter.food", "Еда"),
            Map.entry("filter.tools", "Инструменты"),
            Map.entry("filter.machines", "Механизмы"),
            Map.entry("filter.other", "Прочее"),
            Map.entry("listing.priceLabel", "Цена за лот"),
            Map.entry("listing.quantityLabel", "Количество"),
            Map.entry("listing.sellerLabel", "Продавец"));
    private static final Map<String, ButtonCfg> DEFAULT_BUTTONS = Map.ofEntries(
            Map.entry("previous", button(Items.ARROW, "Предыдущая страница", "")),
            Map.entry("next", button(Items.ARROW, "Следующая страница", "")),
            Map.entry("categories", button(Items.BOOK, "Разделы", "Переключить раздел")),
            Map.entry("refresh", button(Items.CLOCK, "Обновить", "Проверить новые лоты")),
            Map.entry("my", button(Items.ENDER_CHEST, "Мои лоты", "Показать только свои лоты")),
            Map.entry("claim", button(Items.CHEST, "Забрать предметы", "Покупки и возвраты")),
            Map.entry("info", button(Items.PAPER, "Страница {page} / {pages}", "{mode}")),
            Map.entry("empty", button(Items.PAPER, "Подходящих лотов нет", "/ah sell <цена>")));
    private static final List<String> DEFAULT_CARD = List.of(
            "value:listing.price", "value:listing.quantity", "value:listing.seller",
            "empty", "value:listing.action");

    record LineValue(String labelKey, String text, String colorKey) {}
    record ButtonCfg(Item iconItem, String name, List<String> lore,
                     String colorKey, String loreColorKey) {}
    private record Catalogue(int rows, String title, List<Integer> content, Map<String, Integer> controls) {}
    private record Decoration(boolean enabled, boolean fillEmpty, List<Integer> slots,
                              Item icon, int count, String name, List<String> lore,
                              String color, String loreColor) {}
    private record Snapshot(Catalogue catalogue, Map<String, ButtonCfg> buttons,
                            Map<String, String> texts, List<String> card,
                            Decoration decoration, Map<String, String> colors) {}

    private static volatile Path file;
    private static volatile Snapshot current = defaults();

    private UiConfig() {}

    public static void start(Path configDir) {
        try {
            Path root = VAuctionConfigPaths.directory(configDir);
            file = root.resolve("auction-ui.json");
            archiveOldUi(root.resolve("ui"));
            String error = reload();
            if (error != null) LOGGER.error("Cannot load {}: {}", file, error);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot prepare VAuction UI config", e);
        }
    }

    static String reload() {
        if (file == null) return "UI config is not initialised";
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) Files.writeString(file, defaultJson(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Корень auction-ui.json должен быть объектом");
            Snapshot next = parse(parsed.getAsJsonObject());
            current = next;
            MarketPalette.replace(next.colors());
            Files.writeString(file.resolveSibling("AUCTION-UI-README.txt"), readme(), StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            LOGGER.error("Cannot reload {}: {}", file, e.getMessage());
            return e.getMessage();
        }
    }

    static String text(String key) { return current.texts().getOrDefault(key, key); }
    static ButtonCfg button(String key) { return current.buttons().getOrDefault(key, DEFAULT_BUTTONS.get("info")); }
    static int rows(String screen) { requireCatalogue(screen); return current.catalogue().rows(); }
    static String title(String screen, Map<String, String> placeholders) {
        requireCatalogue(screen);
        return format(current.catalogue().title(), placeholders);
    }
    static int slot(String screen, String key) {
        requireCatalogue(screen);
        Integer value = current.catalogue().controls().get(key);
        return value == null ? -1 : value;
    }
    static int[] slots(String screen, String key) {
        requireCatalogue(screen);
        if (!"content".equals(key)) throw new IllegalArgumentException("Unknown catalogue slot list: " + key);
        return current.catalogue().content().stream().mapToInt(Integer::intValue).toArray();
    }

    static void decorate(String screen, SimpleContainer box, Map<String, String> placeholders) {
        requireCatalogue(screen);
        Decoration cfg = current.decoration();
        if (!cfg.enabled()) return;
        ItemStack item = new ItemStack(cfg.icon());
        item.setCount(cfg.count());
        List<Component> lore = cfg.lore().stream().map(line -> MarketText.colored(
                format(line, placeholders), MarketPalette.byKey(cfg.loreColor()))).toList();
        item = GuiItems.namedButton(item, MarketText.colored(format(cfg.name(), placeholders),
                MarketPalette.byKey(cfg.color())), lore);
        for (int slot : cfg.slots()) putDecoration(box, slot, item);
        if (cfg.fillEmpty()) for (int slot = 0; slot < box.getContainerSize(); slot++) {
            putDecoration(box, slot, item);
        }
    }

    static List<Component> lines(String block, LinkedHashMap<String, LineValue> values) {
        if (!"listingCard".equals(block)) throw new IllegalArgumentException("Unknown card: " + block);
        LinkedHashMap<String, String> placeholders = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null && value.text() != null) placeholders.put(key, value.text());
        });
        List<Component> result = new ArrayList<>();
        for (String token : current.card()) {
            if ("empty".equals(token)) result.add(Component.empty());
            else if (token.startsWith("value:")) {
                LineValue value = values.get(token.substring(6));
                if (value != null && value.text() != null && !value.text().isBlank()) {
                    result.add(value.labelKey() == null
                            ? MarketText.colored(format(value.text(), placeholders), MarketPalette.byKey(value.colorKey()))
                            : MarketText.labelValue(text(value.labelKey()), format(value.text(), placeholders),
                            MarketPalette.byKey(value.colorKey())));
                }
            } else if (token.startsWith("text:")) {
                result.add(MarketText.text(format(text(token.substring(5)), placeholders)));
            } else throw new IllegalArgumentException("Unknown listingCard token: " + token);
        }
        return result;
    }

    static String format(String template, Map<String, String> placeholders) {
        String result = template == null ? "" : template;
        if (placeholders != null) for (var entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result.replaceAll("\\{[a-zA-Z0-9_.-]+}", "");
    }

    private static Snapshot parse(JsonObject root) {
        JsonObject catalogue = object(root, "catalogue");
        int rows = integer(catalogue, "rows", 6);
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("catalogue.rows должен быть от 1 до 6");
        int capacity = rows * 9;
        String title = string(catalogue, "title", "Аукцион • {mode} • {page}/{pages}");
        List<Integer> content = integers(catalogue, "content", defaultContent());
        if (content.isEmpty()) throw new IllegalArgumentException("catalogue.content не может быть пустым");
        LinkedHashMap<String, Integer> controls = new LinkedHashMap<>();
        JsonObject controlJson = object(catalogue, "controls");
        for (String key : CONTROL_KEYS) controls.put(key, nullableInteger(controlJson, key,
                defaultControls().get(key)));
        validateSlots(capacity, content, controls);

        LinkedHashMap<String, ButtonCfg> buttons = new LinkedHashMap<>(DEFAULT_BUTTONS);
        JsonObject buttonJson = object(root, "buttons");
        for (String key : buttonJson.keySet()) {
            if (!BUTTON_KEYS.contains(key)) throw new IllegalArgumentException("Неизвестная кнопка: " + key);
            JsonObject value = object(buttonJson, key);
            ButtonCfg base = buttons.get(key);
            Item icon = item(string(value, "icon", BuiltInRegistries.ITEM.getKey(base.iconItem()).toString()));
            String name = string(value, "name", base.name());
            List<String> lore = strings(value, "lore", base.lore());
            buttons.put(key, new ButtonCfg(icon, name, lore,
                    string(value, "color", base.colorKey()),
                    string(value, "loreColor", base.loreColorKey())));
        }

        LinkedHashMap<String, String> texts = new LinkedHashMap<>(DEFAULT_TEXTS);
        JsonObject textJson = object(root, "texts");
        for (String key : textJson.keySet()) texts.put(key, textJson.get(key).getAsString());
        List<String> card = strings(object(root, "listingCard"), "lore", DEFAULT_CARD);
        for (String token : card) if (!(token.equals("empty") || token.startsWith("value:")
                || token.startsWith("text:"))) throw new IllegalArgumentException("Неизвестная строка карточки: " + token);

        JsonObject decorationJson = object(root, "decoration");
        Decoration decoration = new Decoration(
                bool(decorationJson, "enabled", false), bool(decorationJson, "fillEmpty", true),
                integers(decorationJson, "slots", List.of()),
                item(string(decorationJson, "icon", "minecraft:gray_stained_glass_pane")),
                integer(decorationJson, "count", 1), string(decorationJson, "name", " "),
                strings(decorationJson, "lore", List.of()), string(decorationJson, "color", "muted"),
                string(decorationJson, "loreColor", "muted"));
        for (int slot : decoration.slots()) checkSlot(slot, capacity, "decoration.slots");

        LinkedHashMap<String, String> colors = new LinkedHashMap<>(MarketPalette.DEFAULT_COLORS);
        JsonObject colorJson = object(root, "colors");
        for (String key : colorJson.keySet()) colors.put(key, colorJson.get(key).getAsString());
        return new Snapshot(new Catalogue(rows, title, List.copyOf(content), Map.copyOf(controls)),
                Map.copyOf(buttons), Map.copyOf(texts), List.copyOf(card), decoration, Map.copyOf(colors));
    }

    private static void validateSlots(int capacity, List<Integer> content, Map<String, Integer> controls) {
        Set<Integer> used = new java.util.HashSet<>();
        for (int slot : content) {
            checkSlot(slot, capacity, "catalogue.content");
            if (!used.add(slot)) throw new IllegalArgumentException("Duplicate content slot: " + slot);
        }
        for (var entry : controls.entrySet()) {
            Integer slot = entry.getValue();
            if (slot == null || slot < 0) continue;
            checkSlot(slot, capacity, "catalogue.controls." + entry.getKey());
            // The empty-state item exists only when there are no cards, so it may
            // intentionally use a slot from the content area.
            if (!"empty".equals(entry.getKey()) && !used.add(slot)) {
                throw new IllegalArgumentException("Slot " + slot + " overlaps content: " + entry.getKey());
            }
        }
    }

    private static void checkSlot(int slot, int capacity, String path) {
        if (slot < 0 || slot >= capacity) throw new IllegalArgumentException(path + ": слот " + slot
                + " вне размера окна 0.." + (capacity - 1));
    }

    private static Snapshot defaults() { return parse(JsonParser.parseString(defaultJson()).getAsJsonObject()); }

    private static String defaultJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("help", "Единственный настраиваемый экран: catalogue. Подтверждение покупки фиксировано кодом.");
        JsonObject catalogue = new JsonObject();
        catalogue.addProperty("rows", 6);
        catalogue.addProperty("title", "Аукцион • {mode} • {page}/{pages}");
        catalogue.add("content", array(defaultContent()));
        JsonObject controls = new JsonObject();
        defaultControls().forEach((key, value) -> controls.addProperty(key, value));
        catalogue.add("controls", controls);
        root.add("catalogue", catalogue);

        JsonObject buttons = new JsonObject();
        DEFAULT_BUTTONS.forEach((key, cfg) -> {
            JsonObject value = new JsonObject();
            value.addProperty("icon", BuiltInRegistries.ITEM.getKey(cfg.iconItem()).toString());
            value.addProperty("name", cfg.name());
            JsonArray lore = new JsonArray(); cfg.lore().forEach(lore::add); value.add("lore", lore);
            value.addProperty("color", cfg.colorKey());
            value.addProperty("loreColor", cfg.loreColorKey());
            buttons.add(key, value);
        });
        root.add("buttons", buttons);
        JsonObject card = new JsonObject(); card.add("lore", strings(DEFAULT_CARD)); root.add("listingCard", card);
        JsonObject texts = new JsonObject(); DEFAULT_TEXTS.forEach(texts::addProperty); root.add("texts", texts);
        JsonObject decoration = new JsonObject();
        decoration.addProperty("enabled", false); decoration.addProperty("fillEmpty", true);
        decoration.add("slots", new JsonArray()); decoration.addProperty("icon", "minecraft:gray_stained_glass_pane");
        decoration.addProperty("count", 1); decoration.addProperty("name", " "); decoration.add("lore", new JsonArray());
        decoration.addProperty("color", "muted"); decoration.addProperty("loreColor", "muted");
        root.add("decoration", decoration);
        JsonObject colors = new JsonObject(); MarketPalette.DEFAULT_COLORS.forEach(colors::addProperty); root.add("colors", colors);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
    }

    private static String readme() {
        return """
                VAuction: настройка интерфейса

                Файл: auction-ui.json
                Настраивается только основной каталог. Экран подтверждения покупки фиксирован.

                catalogue.rows: 1..6 рядов, по 9 слотов.
                catalogue.title: заголовок. Плейсхолдеры: {player}, {mode}, {category}, {search}, {page}, {pages}, {results}.
                catalogue.content: слоты карточек лотов.
                catalogue.controls: слоты кнопок. Число ставит кнопку, -1 скрывает.
                Доступные кнопки: previous, next, categories, refresh, my, info/claim, empty.
                В один слот нельзя поставить два элемента и нельзя пересекать controls с content.

                buttons: предмет, название, описание и цвета кнопок.
                listingCard.lore: порядок строк карточки. Доступно:
                  value:listing.price
                  value:listing.quantity
                  value:listing.seller
                  value:listing.action
                  empty
                texts: подписи и названия разделов.
                decoration: необязательный фон. fillEmpty заполняет только свободные ячейки,
                поэтому функциональные кнопки и товары всегда остаются поверх него.
                colors: RGB-цвета без символа #.

                После правок: /ah reload
                """;
    }

    private static void archiveOldUi(Path old) throws java.io.IOException {
        if (!Files.isDirectory(old)) return;
        Path marker = old.getParent().resolve("ui-legacy-orderbook");
        if (Files.exists(marker)) {
            int suffix = 2;
            while (Files.exists(old.getParent().resolve("ui-legacy-orderbook-" + suffix))) suffix++;
            marker = old.getParent().resolve("ui-legacy-orderbook-" + suffix);
        }
        try {
            Files.move(old, marker, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(old, marker);
        }
    }

    private static void putDecoration(SimpleContainer box, int slot, ItemStack item) {
        if (slot >= 0 && slot < box.getContainerSize() && box.getItem(slot).isEmpty()) box.setItem(slot, item.copy());
    }
    private static void requireCatalogue(String screen) {
        if (!"catalogue".equals(screen)) throw new IllegalArgumentException("Unknown UI screen: " + screen);
    }
    private static ButtonCfg button(Item item, String name, String lore) {
        return new ButtonCfg(item, name, lore.isBlank() ? List.of() : List.of(lore), "brand", "muted");
    }
    private static List<Integer> defaultContent() {
        List<Integer> result = new ArrayList<>(); for (int i = 0; i < 45; i++) result.add(i); return result;
    }
    private static Map<String, Integer> defaultControls() {
        return Map.of("empty", 22, "previous", 45, "categories", 46, "refresh", 47,
                "info", 49, "my", 50, "next", 53);
    }
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null) return new JsonObject();
        if (!value.isJsonObject()) throw new IllegalArgumentException(key + " должен быть объектом");
        return value.getAsJsonObject();
    }
    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key); return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }
    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key); return value == null || !value.isJsonPrimitive() ? fallback : value.getAsInt();
    }
    private static Integer nullableInteger(JsonObject object, String key, Integer fallback) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (value.isJsonNull()) return -1;
        return value.getAsInt();
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key); return value == null || !value.isJsonPrimitive() ? fallback : value.getAsBoolean();
    }
    private static List<Integer> integers(JsonObject object, String key, List<Integer> fallback) {
        JsonElement value = object.get(key); if (value == null) return List.copyOf(fallback);
        if (!value.isJsonArray()) throw new IllegalArgumentException(key + " должен быть массивом чисел");
        List<Integer> result = new ArrayList<>(); for (JsonElement entry : value.getAsJsonArray()) result.add(entry.getAsInt());
        return result;
    }
    private static List<String> strings(JsonObject object, String key, List<String> fallback) {
        JsonElement value = object.get(key); if (value == null) return List.copyOf(fallback);
        if (!value.isJsonArray()) throw new IllegalArgumentException(key + " должен быть массивом строк");
        List<String> result = new ArrayList<>(); for (JsonElement entry : value.getAsJsonArray()) result.add(entry.getAsString());
        return result;
    }
    private static JsonArray array(List<Integer> values) { JsonArray out = new JsonArray(); values.forEach(out::add); return out; }
    private static JsonArray strings(List<String> values) { JsonArray out = new JsonArray(); values.forEach(out::add); return out; }
    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) throw new IllegalArgumentException("Некорректный id предмета: " + id);
        return BuiltInRegistries.ITEM.getOptional(key).orElseThrow(() ->
                new IllegalArgumentException("Неизвестный предмет кнопки: " + id));
    }
}

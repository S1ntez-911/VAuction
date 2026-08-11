package com.valorcraft.vauction.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.item.Items;
import net.minecraft.server.Bootstrap;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiConfigTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try { Bootstrap.bootStrap(); } catch (Throwable ignored) {}
    }

    @TempDir
    Path temp;

    private Path uiFile() {
        return temp.resolve("VMods").resolve("VAuction").resolve("vauction-ui.json");
    }

    @Test
    void generatedConfigExposesButtonsAndEveryEditableScreenLayout() throws Exception {
        UiConfig.start(temp);
        Path file = uiFile();
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();

        assertEquals(4, root.get("format").getAsInt());
        assertTrue(root.has("placeholderHelp"));
        assertTrue(root.getAsJsonObject("placeholderHelp").getAsJsonObject("screens")
                .getAsJsonObject("product").has("buy_price"));
        assertTrue(root.getAsJsonObject("decorations").getAsJsonObject("product")
                .getAsJsonObject("background").has("fillEmpty"));
        assertTrue(root.getAsJsonObject("buttons").getAsJsonObject("back").has("name"));
        assertTrue(root.getAsJsonObject("buttons").getAsJsonObject("back").has("lore"));
        JsonObject layouts = root.getAsJsonObject("layouts");
        for (String screen : new String[]{"catalogue", "search", "categories", "product",
                "immediate", "limit", "my", "manage"}) {
            assertTrue(layouts.has(screen), "missing editable layout " + screen);
            assertEquals(6, root.getAsJsonObject("screens").getAsJsonObject(screen).get("rows").getAsInt());
        }
        assertTrue(layouts.getAsJsonObject("catalogue").get("content").isJsonArray());
    }

    @Test
    void oldPartialConfigIsExpandedWithoutOverwritingOwnerText() throws Exception {
        Path legacy = temp.resolve("vauction-ui.json");
        Files.writeString(legacy, "{\"format\":2,\"texts\":{\"brand\":\"Моя биржа\"}}", StandardCharsets.UTF_8);

        UiConfig.start(temp);

        Path file = uiFile();
        JsonObject upgraded = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(4, upgraded.get("format").getAsInt());
        assertFalse(Files.exists(legacy));
        assertEquals("Моя биржа", upgraded.getAsJsonObject("texts").get("brand").getAsString());
        assertTrue(upgraded.has("layouts"));
        assertTrue(upgraded.has("screens"));
        assertTrue(upgraded.has("placeholderHelp"));
        assertTrue(upgraded.getAsJsonObject("buttons").has("productBuy"));
    }

    @Test
    void reloadAppliesSlotsButtonTextLoreAndIcon() throws Exception {
        UiConfig.start(temp);
        Path file = uiFile();
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject product = root.getAsJsonObject("layouts").getAsJsonObject("product");
        product.addProperty("item", 13);
        product.addProperty("back", 12);
        product.addProperty("buy", 10);
        product.addProperty("sell", 11);
        JsonObject back = root.getAsJsonObject("buttons").getAsJsonObject("back");
        back.addProperty("icon", "minecraft:oak_door");
        back.addProperty("name", "Вернуться");
        back.getAsJsonArray("lore").set(0, new com.google.gson.JsonPrimitive("На предыдущий экран"));
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        assertNull(UiConfig.reload());
        assertEquals(10, UiConfig.slot("product", "buy"));
        assertEquals(11, UiConfig.slot("product", "sell"));
        assertEquals(Items.OAK_DOOR, UiConfig.button("back").iconItem());
        assertEquals("Вернуться", UiConfig.button("back").name());
        assertEquals("На предыдущий экран", UiConfig.button("back").lore().get(0));
    }

    @Test
    void badReloadKeepsLastKnownGoodLayout() throws Exception {
        UiConfig.start(temp);
        Path file = uiFile();
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject product = root.getAsJsonObject("layouts").getAsJsonObject("product");
        product.addProperty("buy", 10);
        product.addProperty("sell", 11);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        assertNull(UiConfig.reload());

        product.addProperty("buy", 48);
        product.addProperty("sell", 48);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        String error = UiConfig.reload();
        assertNotNull(error);
        assertTrue(error.contains("used twice"));
        assertEquals(10, UiConfig.slot("product", "buy"),
                "failed reload must preserve the previous valid snapshot from this JVM");
    }

    @Test
    void rowsTitlesHiddenSlotsAndPlaceholdersAreConfigurable() throws Exception {
        UiConfig.start(temp);
        Path file = uiFile();
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject screens = root.getAsJsonObject("screens");
        screens.getAsJsonObject("product").addProperty("rows", 3);
        screens.getAsJsonObject("product").addProperty("title", "{item}: {buy_price}");
        JsonObject product = root.getAsJsonObject("layouts").getAsJsonObject("product");
        product.addProperty("item", 13);
        product.addProperty("back", 18);
        product.addProperty("buy", 21);
        product.add("sell", com.google.gson.JsonNull.INSTANCE);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        assertNull(UiConfig.reload());
        assertEquals(3, UiConfig.rows("product"));
        assertEquals(-1, UiConfig.slot("product", "sell"));
        assertEquals("Слиток: 1.40", UiConfig.title("product",
                java.util.Map.of("item", "Слиток", "buy_price", "1.40")));
        assertEquals("Цена 2.50, ", UiConfig.format("Цена {price}, {unknown}",
                java.util.Map.of("price", "2.50")));
    }

    @Test
    void rowReductionRejectsSlotsOutsideNewCapacityAndKeepsPreviousSnapshot() throws Exception {
        UiConfig.start(temp);
        Path file = uiFile();
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        root.getAsJsonObject("screens").getAsJsonObject("product").addProperty("rows", 3);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        String error = UiConfig.reload();
        assertNotNull(error);
        assertTrue(error.contains("outside 0..26"));
        assertEquals(6, UiConfig.rows("product"));
        assertFalse(error.isBlank());
    }

    @Test
    void decorationsFillOnlyEmptySlotsAndResolveScreenPlaceholders() throws Exception {
        UiConfig.start(temp);
        Path file = uiFile();
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject productDecorations = root.getAsJsonObject("decorations").getAsJsonObject("product");
        JsonObject accent = productDecorations.getAsJsonObject("background").deepCopy();
        accent.addProperty("enabled", true);
        accent.addProperty("fillEmpty", false);
        com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
        slots.add(0);
        slots.add(1);
        accent.add("slots", slots);
        accent.addProperty("icon", "minecraft:black_stained_glass_pane");
        accent.addProperty("name", "{item}");
        com.google.gson.JsonArray lore = new com.google.gson.JsonArray();
        lore.add("Покупка: {buy_price}");
        accent.add("lore", lore);
        productDecorations.add("accent", accent);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        assertNull(UiConfig.reload());
        net.minecraft.world.SimpleContainer box = new net.minecraft.world.SimpleContainer(54);
        box.setItem(0, new net.minecraft.world.item.ItemStack(Items.DIAMOND));
        UiConfig.decorate("product", box, java.util.Map.of("item", "Слиток", "buy_price", "1.40"));
        assertEquals(Items.DIAMOND, box.getItem(0).getItem());
        assertEquals(Items.BLACK_STAINED_GLASS_PANE, box.getItem(1).getItem());
        assertEquals("Слиток", box.getItem(1).getHoverName().getString());
        assertTrue(box.getItem(2).isEmpty());
    }

    @Test
    void unavailableScreenPlaceholderRejectsReload() throws Exception {
        UiConfig.start(temp);
        Path file = uiFile();
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        root.getAsJsonObject("screens").getAsJsonObject("product").addProperty("title", "{price}");
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);

        String error = UiConfig.reload();
        assertNotNull(error);
        assertTrue(error.contains("screens.product.title"));
        assertTrue(error.contains("unavailable placeholder {price}"));
    }
}

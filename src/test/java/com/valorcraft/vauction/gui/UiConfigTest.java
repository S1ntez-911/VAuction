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

    @Test
    void generatedConfigExposesButtonsAndEveryEditableScreenLayout() throws Exception {
        UiConfig.start(temp);
        Path file = temp.resolve("vauction-ui.json");
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();

        assertEquals(2, root.get("format").getAsInt());
        assertTrue(root.getAsJsonObject("buttons").getAsJsonObject("back").has("name"));
        assertTrue(root.getAsJsonObject("buttons").getAsJsonObject("back").has("lore"));
        JsonObject layouts = root.getAsJsonObject("layouts");
        for (String screen : new String[]{"catalogue", "search", "categories", "product",
                "immediate", "limit", "my", "manage"}) {
            assertTrue(layouts.has(screen), "missing editable layout " + screen);
        }
        assertTrue(layouts.getAsJsonObject("catalogue").get("content").isJsonArray());
    }

    @Test
    void oldPartialConfigIsExpandedWithoutOverwritingOwnerText() throws Exception {
        Path file = temp.resolve("vauction-ui.json");
        Files.writeString(file, "{\"texts\":{\"brand\":\"Моя биржа\"}}", StandardCharsets.UTF_8);

        UiConfig.start(temp);

        JsonObject upgraded = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("Моя биржа", upgraded.getAsJsonObject("texts").get("brand").getAsString());
        assertTrue(upgraded.has("layouts"));
        assertTrue(upgraded.getAsJsonObject("buttons").has("productBuy"));
    }

    @Test
    void reloadAppliesSlotsButtonTextLoreAndIcon() throws Exception {
        UiConfig.start(temp);
        Path file = temp.resolve("vauction-ui.json");
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
        Path file = temp.resolve("vauction-ui.json");
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
}

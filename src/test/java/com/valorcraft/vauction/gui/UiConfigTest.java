package com.valorcraft.vauction.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UiConfigTest {
    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try { Bootstrap.bootStrap(); } catch (Throwable ignored) {}
    }

    @TempDir Path temp;
    private Path root() { return temp.resolve("VMods").resolve("VAuction"); }
    private Path file() { return root().resolve("auction-ui.json"); }

    @Test
    void generatesOneUiFileForOneConfigurableScreen() throws Exception {
        UiConfig.start(temp);
        assertTrue(Files.isRegularFile(file()));
        assertTrue(Files.isRegularFile(root().resolve("AUCTION-UI-README.txt")));
        JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(json.has("catalogue"));
        assertTrue(json.has("listingCard"));
        assertFalse(json.has("screens"));
        for (String old : new String[]{"search", "categories", "product", "immediate", "limit", "my", "manage"}) {
            assertFalse(json.has(old), "legacy screen leaked into new config: " + old);
        }
        assertEquals(45, UiConfig.slots("catalogue", "content").length);
    }

    @Test
    void reloadChangesRowsTitleSlotsAndButton() throws Exception {
        UiConfig.start(temp);
        JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject catalogue = json.getAsJsonObject("catalogue");
        catalogue.addProperty("rows", 5);
        catalogue.addProperty("title", "Лоты {page}/{pages}");
        catalogue.getAsJsonObject("controls").addProperty("previous", 36);
        catalogue.getAsJsonObject("controls").addProperty("categories", 37);
        catalogue.getAsJsonObject("controls").addProperty("refresh", 38);
        catalogue.getAsJsonObject("controls").addProperty("info", 40);
        catalogue.getAsJsonObject("controls").addProperty("my", 41);
        catalogue.getAsJsonObject("controls").addProperty("next", 44);
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        for (int i = 0; i < 36; i++) content.add(i);
        catalogue.add("content", content);
        JsonObject refresh = json.getAsJsonObject("buttons").getAsJsonObject("refresh");
        refresh.addProperty("icon", "minecraft:diamond"); refresh.addProperty("name", "Ещё раз");
        Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);

        assertNull(UiConfig.reload());
        assertEquals(5, UiConfig.rows("catalogue"));
        assertEquals(36, UiConfig.slot("catalogue", "previous"));
        assertEquals("Лоты 2/4", UiConfig.title("catalogue", Map.of("page", "2", "pages", "4")));
        assertEquals(Items.DIAMOND, UiConfig.button("refresh").iconItem());
        assertEquals("Ещё раз", UiConfig.button("refresh").name());
    }

    @Test
    void invalidOverlapKeepsLastGoodSnapshot() throws Exception {
        UiConfig.start(temp);
        assertEquals(45, UiConfig.slot("catalogue", "previous"));
        JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
        json.getAsJsonObject("catalogue").getAsJsonObject("controls").addProperty("previous", 0);
        Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
        assertNotNull(UiConfig.reload());
        assertEquals(45, UiConfig.slot("catalogue", "previous"));
    }

    @Test
    void oldUiDirectoryIsArchivedAndNeverLoaded() throws Exception {
        Path old = root().resolve("ui");
        Files.createDirectories(old);
        Files.writeString(old.resolve("screens.json"), "{broken", StandardCharsets.UTF_8);
        UiConfig.start(temp);
        assertFalse(Files.exists(old));
        assertTrue(Files.isDirectory(root().resolve("ui-legacy-orderbook")));
        assertNull(UiConfig.reload());
    }

    @Test
    void cardOrderDecorationAndPlaceholdersWork() throws Exception {
        UiConfig.start(temp);
        JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject decoration = json.getAsJsonObject("decoration");
        decoration.addProperty("enabled", true); decoration.addProperty("fillEmpty", true);
        decoration.addProperty("icon", "minecraft:black_stained_glass_pane");
        Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
        assertNull(UiConfig.reload());
        SimpleContainer box = new SimpleContainer(54);
        UiConfig.decorate("catalogue", box, Map.of("mode", "Все лоты"));
        assertEquals(Items.BLACK_STAINED_GLASS_PANE, box.getItem(0).getItem());

        LinkedHashMap<String, UiConfig.LineValue> values = new LinkedHashMap<>();
        values.put("listing.price", new UiConfig.LineValue("listing.priceLabel", "1.40", "money"));
        values.put("listing.quantity", new UiConfig.LineValue("listing.quantityLabel", "8", "text"));
        values.put("listing.seller", new UiConfig.LineValue("listing.sellerLabel", "Alex", "muted"));
        values.put("listing.action", new UiConfig.LineValue(null, "Купить", "success"));
        assertEquals(5, UiConfig.lines("listingCard", values).size());
    }
}

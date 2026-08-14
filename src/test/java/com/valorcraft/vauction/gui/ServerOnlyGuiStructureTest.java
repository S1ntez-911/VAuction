package com.valorcraft.vauction.gui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Player-facing regression contract for the deliberately small auction UI. */
class ServerOnlyGuiStructureTest {
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java").resolve(relative), StandardCharsets.UTF_8);
    }

    @Test
    void aliasesExposeOnlyFixedPricePlayerFlow() throws Exception {
        String commands = source("com/valorcraft/vauction/gui/MarketCommands.java");
        assertTrue(commands.contains("root(\"ah\")"));
        assertTrue(commands.contains("root(\"auction\")"));
        assertTrue(commands.contains("root(\"market\")"));
        assertTrue(commands.contains("Commands.literal(\"sell\")"));
        assertTrue(commands.contains("Commands.literal(\"search\")"));
        assertTrue(commands.contains("Commands.literal(\"mine\")"));
        assertFalse(commands.contains("Commands.literal(\"buy\")"));
        assertFalse(commands.contains("Commands.literal(\"orders\")"));
        assertFalse(commands.contains("Commands.literal(\"set\")"));
    }

    @Test
    void sellListsTheWholeHeldStackAtOneTotalPrice() throws Exception {
        String commands = source("com/valorcraft/vauction/gui/MarketCommands.java");
        assertTrue(commands.contains("stack.copy(), price"));
        assertTrue(commands.contains("Цена за весь стек"));
        assertFalse(commands.contains("requestedQuantity"));
    }

    @Test
    void uiHasOneCatalogueAndOnePurchaseConfirmation() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertTrue(controller.contains("renderCatalogue"));
        assertTrue(controller.contains("CONFIRM_PURCHASE"));
        assertTrue(controller.contains("TOGGLE_MINE"));
        assertTrue(controller.contains("NEXT_CATEGORY"));
        assertTrue(controller.contains("CLAIM_ALL"));
        assertFalse(controller.contains("renderProduct"));
        assertFalse(controller.contains("renderEditor"));
        assertFalse(controller.contains("renderMy"));
        assertFalse(controller.contains("beginImmediate"));
    }

    @Test
    void ownListingCancelsAndForeignListingRequiresConfirmation() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertTrue(controller.contains("listing.sellerUuid().equals(player.getUUID())"));
        assertTrue(controller.contains("cancel(player, s, listingId)"));
        assertTrue(controller.contains("Купить лот?"));
        assertTrue(controller.contains("CONFIRM_PRIMARY"));
    }

    @Test
    void vanillaMenuRejectsEveryInventoryMovementPath() throws Exception {
        String menu = source("com/valorcraft/vauction/gui/ServerChestMenu.java");
        assertTrue(menu.contains("clickType != ClickType.PICKUP"));
        assertTrue(menu.contains("return ItemStack.EMPTY"));
        assertTrue(menu.contains("return false"));
    }

    @Test
    void catalogueAlwaysRendersBothPageArrows() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertTrue(controller.contains("put(box, s, previous, arrow(s, false, page.hasPrevious())"));
        assertTrue(controller.contains("put(box, s, next, arrow(s, true, page.hasNext())"));
        assertTrue(controller.contains("Других страниц нет"));
    }

    @Test
    void exactItemIsKeptInStorageWhileTooltipUsesCleanCard() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String items = source("com/valorcraft/vauction/gui/GuiItems.java");
        assertTrue(controller.contains("codec().decode(listing.item())"));
        assertTrue(controller.contains("GuiItems.marketDisplay"));
        assertTrue(controller.contains("GuiItems.decorateMarketItem"));
        assertTrue(items.contains("new ItemStack(realItem.getItem())"));
        assertTrue(items.contains("visual.setCount(Math.max(1, realItem.getCount()))"));
    }

    @Test
    void guiUsesNoCustomNetworkingOrClientScreen() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertTrue(controller.contains("SimpleMenuProvider"));
        assertTrue(controller.contains("ClientboundContainerSetContentPacket"));
        assertFalse(controller.contains("SimpleChannel"));
        assertFalse(controller.contains("NetworkRegistry"));
    }
}

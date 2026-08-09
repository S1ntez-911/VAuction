package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.domain.market.MarketSummary;
import com.valorcraft.vauction.domain.order.OrderSide;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerOnlyGuiStructureTest {
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java").resolve(relative), StandardCharsets.UTF_8);
    }

    @Test
    void allMarketCommandAliasesShareTheSameCommandTree() throws Exception {
        String commands = source("com/valorcraft/vauction/gui/MarketCommands.java");
        assertTrue(commands.contains("register(root(\"market\", context))"));
        assertTrue(commands.contains("register(root(\"auction\", context))"));
        assertTrue(commands.contains("register(root(\"ah\", context))"));
        assertTrue(commands.contains("Commands.literal(\"help\")"));
        assertTrue(commands.contains("Commands.literal(\"sell\")"));
        assertTrue(commands.contains("Commands.literal(\"buy\")"));
        assertTrue(commands.contains("ItemArgument.item(context)"));
    }

    @Test
    void menuUsesVanillaTypeAndDeniesEveryMovementPath() throws Exception {
        String menu = source("com/valorcraft/vauction/gui/ServerChestMenu.java");
        assertTrue(menu.contains("MenuType.GENERIC_9x6"));
        assertTrue(menu.contains("void clicked"), "pickup, swap, throw and double-click share clicked");
        assertTrue(menu.contains("quickMoveStack"), "shift-click must be denied");
        assertTrue(menu.contains("canDragTo"), "drag must be denied");
        assertTrue(menu.contains("canTakeItemForPickAll"), "pick-all must be denied");
        assertTrue(menu.contains("return ItemStack.EMPTY"));
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertTrue(controller.contains("clickType != ClickType.PICKUP"));
        assertTrue(controller.contains("button != 0 && button != 1"));
    }

    @Test
    void productionSourcesContainNoCustomChannelClientScreenOrSecondMod() throws Exception {
        String mods = Files.readString(Path.of("src/main/resources/META-INF/mods.toml"));
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertFalse(mods.contains("exchange_core"));
        assertFalse(controller.contains("SimpleChannel"));
        assertFalse(controller.contains("MenuScreens"));
        Path legacy = Path.of("src/main/java/com/valorcraft/exchange");
        boolean hasLegacyJava = Files.exists(legacy) && Files.walk(legacy)
                .anyMatch(path -> path.toString().endsWith(".java"));
        assertFalse(hasLegacyJava);
    }

    @Test
    void refreshIsExplicitAndReadBudgetsAreHardCapped() throws Exception {
        String read = source("com/valorcraft/vauction/application/AuctionReadService.java");
        String events = source("com/valorcraft/vauction/gui/MarketEvents.java");
        assertTrue(read.contains("PAGE_SIZE = 28"));
        assertTrue(read.contains("BOOK_DEPTH = 7"));
        assertFalse(events.contains("ServerTickEvent"));
    }

    @Test
    void sellConfirmationChecksRequestBeforeAnyInventoryRemoval() throws Exception {
        String service = source("com/valorcraft/vauction/application/AuctionService.java");
        int requestGuard = service.indexOf("repeatedRequest(requestId, seller.getUUID(), OrderSide.SELL)");
        int inventoryRemoval = service.indexOf("inventory.tryTake(sellerId, unit, quantity)");
        assertTrue(requestGuard >= 0 && inventoryRemoval > requestGuard,
                "a repeated sell confirmation must return before item custody is touched");
    }

    @Test
    void navigationReusesOneOpenMenuAndKnownMarketSellDoesNotReturnToPicker() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertTrue(controller.contains("player.containerMenu == s.menu"));
        assertTrue(controller.contains("s.menu.broadcastChanges()"));
        assertTrue(controller.contains("createSellOrderFromInventory(player, s.unit"));
        int beginOrder = controller.indexOf("private void beginOrder");
        int renderEditor = controller.indexOf("private void renderEditor");
        String body = controller.substring(beginOrder, renderEditor);
        assertFalse(body.contains("renderPicker"), "known exact market must open the sell editor directly");
    }

    @Test
    void playerHubExposesImmediateAndLimitFlowsWithoutFakePrices() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String service = source("com/valorcraft/vauction/application/AuctionService.java");
        assertTrue(controller.contains("Купить сейчас"));
        assertTrue(controller.contains("Продать сейчас"));
        assertTrue(controller.contains("Заявка на покупку"));
        assertTrue(controller.contains("Заявка на продажу"));
        assertTrue(controller.contains("renderImmediateQuote"));
        assertTrue(service.contains("finishImmediate"));
        assertFalse(controller.contains("s.price = Long.MAX_VALUE"));
        assertFalse(controller.contains("s.price = 1; // immediate"));
    }

    @Test
    void notificationFeedbackIsDebouncedAndBounded() throws Exception {
        String notifications = source("com/valorcraft/vauction/application/MarketNotificationService.java");
        assertTrue(notifications.contains("FLUSH_INTERVAL_TICKS = 60"));
        assertTrue(notifications.contains("MAX_BATCHES_PER_FLUSH = 32"));
        assertTrue(notifications.contains("if (player == null) continue"));
        assertTrue(notifications.contains("states.advance"));
    }

    @Test
    void conservativePriceWarningNeedsTwoReferencesAndNeverChangesPrice() {
        MarketSummary normal = new MarketSummary("key", "Copper", 31, 33, 10, 10, 32);
        assertFalse(MarketController.shouldWarnPrice(OrderSide.BUY, 35, normal));
        assertTrue(MarketController.shouldWarnPrice(OrderSide.BUY, 320, normal));
        assertTrue(MarketController.shouldWarnPrice(OrderSide.SELL, 3, normal));
        MarketSummary thin = new MarketSummary("key", "Copper", 0, 31, 0, 10, 0);
        assertFalse(MarketController.shouldWarnPrice(OrderSide.BUY, 1000, thin));
        assertEquals(320L, 320L, "warning is advisory and never rewrites the entered price");
    }
}

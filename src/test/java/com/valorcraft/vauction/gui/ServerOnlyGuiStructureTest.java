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
        assertTrue(commands.contains("Commands.literal(\"quantity\")"));
        assertTrue(commands.contains("Commands.literal(\"price\")"));
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
        assertTrue(read.contains("PAGE_SIZE = 45"));
        assertTrue(read.contains("markets.count"));
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
    void marketUsesProgressiveImmediateAndOwnPriceFlowsWithoutFakePrices() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String service = source("com/valorcraft/vauction/application/AuctionService.java");
        assertTrue(controller.contains("Купить сейчас"));
        assertTrue(controller.contains("Продать сейчас"));
        assertTrue(controller.contains("Своя цена"));
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
    void immediateExecutionIsTargetedAndCleanupSharesTickBudget() throws Exception {
        String service = source("com/valorcraft/vauction/application/AuctionService.java");
        String limits = source("com/valorcraft/vauction/application/AuctionWorkLimits.java");
        String events = source("com/valorcraft/vauction/bootstrap/ServerEvents.java");
        int targetedStart = service.indexOf("private Outcome continueImmediateMatching");
        int cleanupStart = service.indexOf("public int finishImmediateRemainders", targetedStart);
        String targeted = service.substring(targetedStart, cleanupStart);
        assertTrue(targeted.contains("pumpImmediateOrder"));
        assertFalse(targeted.contains("pumpMatching("));
        assertTrue(limits.contains("MAX_IMMEDIATE_MATCH_FILLS = 32"));
        assertTrue(events.contains("finishImmediateRemainders(budget, 16)"));
    }

    @Test
    void rootOpensCatalogueAndListNavigationUsesFixedLastRow() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        int open = controller.indexOf("public void open(ServerPlayer player)");
        int search = controller.indexOf("public void search", open);
        String root = controller.substring(open, search);
        assertTrue(root.contains("renderMarkets(player, session)"));
        assertFalse(root.contains("renderHome"));
        assertTrue(controller.contains("IntStream.range(0, 45)"));
        assertTrue(controller.contains("NAV_PREVIOUS = 45"));
        assertTrue(controller.contains("NAV_NEXT = 53"));
        assertTrue(controller.contains("page.totalPages()"));
    }

    @Test
    void realMarketItemsUseNativeSafeDecoratorAndMenuIsOpenedOnlyOnce() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String items = source("com/valorcraft/vauction/gui/GuiItems.java");
        assertTrue(controller.contains("GuiItems.decorateMarketItem(visual"));
        assertTrue(items.contains("result = source.copy()"));
        assertTrue(items.contains("getList(\"Lore\""));
        assertFalse(items.substring(items.indexOf("decorateMarketItem")).contains("setHoverName"));
        assertFalse(items.substring(items.indexOf("decorateMarketItem")).contains("MarketText.brand()"),
                "branding must not repeat on every catalogue card");
        assertEquals(1, controller.split("player\\.openMenu", -1).length - 1);
    }

    @Test
    void listNavigationIsContextualInsteadOfRepeatingCurrentDestination() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String catalogue = between(controller, "private static void catalogueNavigation", "private static void searchNavigation");
        String search = between(controller, "private static void searchNavigation", "private static void myNavigation");
        String my = between(controller, "private static void myNavigation", "private static void pageEdges");
        assertFalse(catalogue.contains("\"Каталог\""), "catalogue must not link to itself");
        assertFalse(catalogue.contains("Продать"));
        assertFalse(catalogue.contains("Получить"));
        assertTrue(catalogue.contains("searchNav"));
        assertTrue(catalogue.contains("myNav"));
        assertTrue(search.contains("\"Все товары\""), "search must offer filter reset");
        assertTrue(my.contains("\"Каталог\""));
    }

    @Test
    void pageClickHasOnlySemanticPageSoundAndQuantityUsesDirectPresets() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String actions = source("com/valorcraft/vauction/gui/GuiAction.java");
        assertTrue(controller.contains("case PAGE ->"));
        assertTrue(controller.contains("MarketSounds.page(player); refreshCurrent(player, s)"));
        assertFalse(controller.contains("a.type() == GuiAction.Type.PAGE"));
        assertTrue(actions.contains("SET_QUANTITY"));
        assertTrue(actions.contains("SET_MAX_QUANTITY"));
        assertTrue(controller.contains("quantityPreset(box, s, 23, 64)"));
        String all = between(controller, "private void setMaximumQuantity", "private void renderMarkets");
        assertTrue(all.contains("service().availableCount"), "ALL must read current server inventory on every click");
    }

    @Test
    void catalogueClickIntentOpensTradeWithoutLegacyMarketScreen() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String actions = source("com/valorcraft/vauction/gui/GuiAction.java");
        assertTrue(actions.contains("OPEN_TRADE"));
        assertFalse(actions.contains("OPEN_MARKET"));
        assertTrue(controller.contains("button == 1 ? OrderSide.SELL : OrderSide.BUY"));
        assertTrue(controller.contains("MarketText.colored(\"ЛКМ → купить\""));
        assertTrue(controller.contains("MarketText.colored(\"ПКМ → продать\""));
        assertFalse(controller.contains("private void renderMarket("));
        assertFalse(controller.contains("private static void levelItems("));
    }

    @Test
    void tradeModesReuseOneLayoutFamilyAndKeepBothLimitOrderBackends() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String screens = source("com/valorcraft/vauction/gui/MarketScreen.java");
        assertTrue(screens.contains("TRADE_IMMEDIATE"));
        assertTrue(screens.contains("TRADE_LIMIT"));
        assertTrue(screens.contains("PRICE_WARNING"));
        assertTrue(controller.contains("private void renderImmediateQuote"));
        assertTrue(controller.contains("private void renderEditor"));
        assertTrue(controller.contains("Своя цена"));
        assertTrue(controller.contains("✓ Выставить заявку"));
        assertTrue(controller.contains("Точно: /ah quantity <число>"));
        assertTrue(controller.contains("Точно: /ah price <число>"));
        assertTrue(controller.contains("confirmOrder(player, s);"), "normal limit submission has no mandatory confirmation page");
        assertTrue(controller.contains("createBuyOrder(player.getUUID(), s.unit"));
        assertTrue(controller.contains("createSellOrderFromInventory(player, s.unit"));
    }

    @Test
    void myIsOneBoundedRelevantFeedForOrdersAndDeliveries() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String read = source("com/valorcraft/vauction/application/AuctionReadService.java");
        String repository = source("com/valorcraft/vauction/persistence/PlayerMarketActivityReadRepository.java");
        assertTrue(controller.contains("private void renderMy"));
        assertFalse(controller.contains("private void renderOrders"));
        assertFalse(controller.contains("private void renderDeliveries"));
        assertTrue(read.contains("playerActivity"));
        assertTrue(repository.contains("status IN ('ACTIVE','MANUAL_REVIEW')"));
        assertTrue(repository.contains("state='CLAIMABLE'"));
        assertTrue(repository.contains("ORDER BY priority, sort_time DESC, tie_key DESC LIMIT ? OFFSET ?"));
        assertTrue(controller.contains("case CLAIM -> claim"));
        assertTrue(controller.contains("case MANAGE_ORDER -> manageOrder"));
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

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
    }
}

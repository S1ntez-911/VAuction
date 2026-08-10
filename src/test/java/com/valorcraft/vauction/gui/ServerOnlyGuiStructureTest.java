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
    void setCommandIsTheSingleContextualInputAndIsHiddenWithoutDraft() throws Exception {
        String commands = source("com/valorcraft/vauction/gui/MarketCommands.java");
        assertTrue(commands.contains("Commands.literal(\"set\")"), "one contextual /ah set input");
        assertTrue(commands.contains(".requires(MarketCommands::inputDraftActive)"),
                "set must be invisible without an active input draft");
        assertTrue(commands.contains("MarketController.instance().setExact(player, value)"));
        assertFalse(commands.contains("literal(\"quantity\")"),
                "per-field commands must not leak into the public tree");
        assertFalse(commands.contains("literal(\"price\")"));
    }

    @Test
    void publicHelpIsHumanReadableAndHidesTechnicalVocabulary() throws Exception {
        String commands = source("com/valorcraft/vauction/gui/MarketCommands.java");
        String main = between(commands, "private static int help(CommandSourceStack source)",
                "private static int helpCommands");
        assertTrue(main.contains("Как купить:"), "help must explain the game, not the command tree");
        assertTrue(main.contains("Как продать:"));
        assertTrue(main.contains("ЛКМ"));
        assertTrue(main.contains("ПКМ"));
        assertTrue(main.contains("Своя цена:"));
        assertTrue(main.contains("Моё:"));
        assertTrue(main.contains("Открыть биржу"));
        assertTrue(main.contains("/ah search <название>"));
        assertFalse(main.contains("quantity"), "no developer vocabulary in the public help");
        assertFalse(main.contains("claim"));
        assertFalse(main.contains("UUID"));
        assertFalse(main.contains("/market"), "main help must not advertise aliases");
        assertFalse(main.contains("maxPrice"));
        assertFalse(main.contains("NBT"));
        assertFalse(main.contains("/ah sell"), "only the GUI flow belongs in the public help");
        assertFalse(main.contains("/ah buy"));
    }

    @Test
    void advancedHelpOnlyShowsFallbackCommands() throws Exception {
        String commands = source("com/valorcraft/vauction/gui/MarketCommands.java");
        String advanced = between(commands, "private static int helpCommands", "private static int helpSell");
        assertTrue(advanced.contains("/ah search <название>"));
        assertTrue(advanced.contains("/ah sell <цена> [количество]"));
        assertTrue(advanced.contains("/ah buy <предмет> <количество> <цена>"));
        assertFalse(advanced.contains("/ah set"));
        assertFalse(advanced.contains("/ah claim"));
        assertFalse(advanced.contains("/ah cancel"));
        assertFalse(advanced.contains("UUID"));
        assertFalse(advanced.contains("deliveryId"));
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
    void navigationReusesOneOpenMenuAndEveryRenderPushesFullSync() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertTrue(controller.contains("player.containerMenu == s.menu"));
        int openBox = controller.indexOf("private void openBox");
        String box = controller.substring(openBox);
        assertTrue(box.contains("fullSync(player, s.menu)"));
        assertTrue(box.contains("ClientboundContainerSetContentPacket"));
        assertTrue(box.contains("menu.getItems()"));
        assertFalse(box.contains("broadcastChanges"), "per-slot deltas must not replace the full sync");
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
        assertTrue(catalogue.contains("catalogueInfo"));
        assertTrue(catalogue.contains("\"Моё\""), "catalogue must link to «Моё»");
        assertTrue(search.contains("\"Новый поиск\""), "search must offer a fresh query");
        assertTrue(search.contains("\"Все товары\""), "search must offer returning to the whole catalogue");
        assertTrue(search.contains("GuiAction.simple(GuiAction.Type.BROWSE)"));
        assertFalse(search.contains("\"Моё\""), "«Моё» is not part of the search task");
        assertTrue(my.contains("\"Каталог\""));
        assertTrue(my.contains("myInfo"));
    }

    @Test
    void catalogueAndBottomRowsUseOneFixedSpatialGrammar() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        assertTrue(controller.contains("NAV_PREVIOUS = 45"));
        assertTrue(controller.contains("NAV_SEARCH = 47"));
        assertTrue(controller.contains("NAV_INFO = 49"));
        assertTrue(controller.contains("NAV_MY = 51"));
        assertTrue(controller.contains("NAV_NEXT = 53"));
        assertTrue(controller.contains("TRADE_BACK = 45"));
        assertTrue(controller.contains("TRADE_SECONDARY = 47"));
        assertTrue(controller.contains("TRADE_PRIMARY = 49"));
        assertTrue(controller.contains("PRICE_INFO = 31"));
        assertFalse(controller.contains("PRICE_MINUS"), "percentage steppers are gone");
        assertFalse(controller.contains("PRICE_PLUS"));
        assertTrue(controller.contains("put(box, s, TRADE_PRIMARY, button(buy ? MarketIcons.PRIMARY_BUY"));
        assertTrue(controller.contains("put(box, s, TRADE_PRIMARY, button(MarketIcons.SUBMIT_LIMIT"));
        assertTrue(controller.contains("put(box, s, TRADE_SECONDARY, button(MarketIcons.MODE_SWITCH"));
        assertTrue(controller.contains("put(box, s, NAV_INFO"));
        assertTrue(controller.contains("MarketIcons.INFO_BOOK"), "info slot must not use raw materials");
    }

    @Test
    void firstOpenAlwaysReceivesFullMenuSyncSoNavigationIsVisibleFromFirstFrame() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        int openBox = controller.indexOf("private void openBox");
        String box = controller.substring(openBox);
        assertTrue(box.contains("player.openMenu"));
        assertTrue(box.contains("if (s.menu != null) fullSync(player, s.menu)"),
                "fresh menu open must push full content immediately");
        assertTrue(controller.contains("private static void fullSync"));
    }

    @Test
    void quantityRowsAreTwoPresetsPlusCustomAndNoRowEverGrowsBeyondThat() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        int buyStart = controller.indexOf("if (side == OrderSide.BUY) {");
        int elseStart = controller.indexOf("} else {", buyStart);
        String buy = controller.substring(buyStart, elseStart);
        assertEquals(2, count(buy, "quantityPreset(box, s,"),
                "BUY must offer exactly [1] [64]");
        assertTrue(buy.contains("quantityPreset(box, s, 21, 1)"));
        assertTrue(buy.contains("quantityPreset(box, s, 22, 64)"));
        String sell = controller.substring(elseStart, controller.indexOf("put(box, s, 23, button(MarketIcons.EXACT"));
        assertEquals(1, count(sell, "quantityPreset(box, s,"),
                "SELL must offer exactly [1] [Всё]");
        assertTrue(controller.contains("MarketText.action(\"Всё\""));
        assertTrue(controller.contains("put(box, s, 23, button(MarketIcons.EXACT"));
        assertTrue(controller.contains("MarketText.action(\"Другое\""));
        assertTrue(controller.contains("icon.setCount(Math.min(quantity, 64))"),
                "presets must show their number on the item sprite");
        assertFalse(controller.contains("quantityPreset(box, s, 20, 1)"));
        assertFalse(controller.contains(", 16)"), "mid presets are visual noise");
        assertFalse(controller.contains(", 32)"));
        assertFalse(controller.contains("Items.RED_DYE"));
        assertFalse(controller.contains("Items.LIME_DYE"));
        assertFalse(controller.contains("ADJUST_QUANTITY\""));
    }

    @Test
    void buttonsNeverUseRawMaterialIcons() throws Exception {
        String forbidden = "(GLOWSTONE_DUST|REDSTONE|RED_DYE|LIME_DYE|GOLD_INGOT|IRON_INGOT"
                + "|COPPER_INGOT|NETHERITE_INGOT|GOLD_NUGGET|IRON_NUGGET|RAW_GOLD|RAW_IRON"
                + "|RAW_COPPER|DIAMOND)";
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Items\\." + forbidden).matcher(controller);
        assertFalse(matcher.find(), "raw-material icon used as a GUI button");
        String icons = source("com/valorcraft/vauction/gui/MarketIcons.java");
        assertTrue(icons.contains("FORBIDDEN"));
        assertTrue(icons.contains("Items.GLOWSTONE_DUST"));
    }

    @Test
    void exactQuantityAndPriceSurviveMenuCloseViaOneTimedDraftLifecycle() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String draft = source("com/valorcraft/vauction/gui/TradeDraft.java");
        assertTrue(controller.contains("EXACT_QUANTITY"));
        assertTrue(controller.contains("EXACT_PRICE"));
        assertTrue(controller.contains("ClickEvent.Action.SUGGEST_COMMAND"));
        assertTrue(controller.contains("/ah set <число>"));
        assertFalse(controller.contains("/ah quantity <число>"));
        assertFalse(controller.contains("/ah price <число>"));
        assertTrue(controller.contains("private void beginExactInput"));
        assertTrue(controller.contains("Укажите своё количество:"));
        assertTrue(controller.contains("Введите цену за штуку:"));
        assertTrue(controller.contains("draft.expectedInput == TradeDraft.InputTarget.PRICE"));
        assertTrue(controller.contains("reopenFromDraft(player, draft)"));
        assertTrue(controller.contains("!draft.expired()"));
        assertTrue(controller.contains("drafts.remove(player.getUUID())"));
        assertTrue(draft.contains("enum InputTarget"));
        assertTrue(draft.contains("QUANTITY"));
        assertTrue(draft.contains("PRICE"));
        assertTrue(draft.contains("TTL_MILLIS = 5 * 60 * 1000L"));
        assertTrue(draft.contains("boolean expired()"));
        assertTrue(controller.contains("session.pendingRequestId = UUID.randomUUID()"),
                "every reopen issues a fresh request id");
    }

    @Test
    void priceWarningKeepsBothReferencesAndNeverChangesPrice() {
        MarketSummary normal = new MarketSummary("key", "Copper", 31, 33, 10, 10, 32);
        assertFalse(MarketController.shouldWarnPrice(OrderSide.BUY, 35, normal));
        assertTrue(MarketController.shouldWarnPrice(OrderSide.BUY, 320, normal));
        assertTrue(MarketController.shouldWarnPrice(OrderSide.SELL, 3, normal));
        MarketSummary thin = new MarketSummary("key", "Copper", 0, 31, 0, 10, 0);
        assertFalse(MarketController.shouldWarnPrice(OrderSide.BUY, 1000, thin));
        assertEquals(320L, 320L, "warning is advisory and never rewrites the entered price");
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
        assertTrue(controller.contains("quantityPreset(box, s, 22, 64)"));
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
    void tradeScreensKeepThreeZoneGrammarAndBothLimitOrderBackends() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String screens = source("com/valorcraft/vauction/gui/MarketScreen.java");
        String actions = source("com/valorcraft/vauction/gui/GuiAction.java");
        assertTrue(screens.contains("TRADE_IMMEDIATE"));
        assertTrue(screens.contains("TRADE_LIMIT"));
        assertTrue(screens.contains("PRICE_WARNING"));
        assertTrue(controller.contains("private void renderImmediateQuote"));
        assertTrue(controller.contains("private void renderEditor"));
        assertTrue(controller.contains("Своя цена"));
        assertTrue(controller.contains("✓ Выставить заявку"));
        assertTrue(controller.contains("confirmOrder(player, s);"), "normal limit submission has no mandatory confirmation page");
        assertTrue(controller.contains("createBuyOrder(player.getUUID(), s.unit"));
        assertTrue(controller.contains("createSellOrderFromInventory(player, s.unit"));
        assertFalse(actions.contains("ADJUST_PRICE_PERCENT"), "percentage action is gone");
        assertFalse(actions.contains("BEST_PRICE"));
        assertTrue(actions.contains("EXACT_PRICE"));
        assertTrue(actions.contains("EXACT_QUANTITY"));
    }

    @Test
    void limitScreenShowsOnePriceEditButtonAndNoPercentSteppers() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String editor = between(controller, "private void renderEditor", "private void reviewOrSubmit");
        assertFalse(editor.contains("-10%"));
        assertFalse(editor.contains("+10%"));
        assertFalse(editor.contains("ADJUST_PRICE_PERCENT"));
        assertTrue(editor.contains("Цена за штуку"));
        assertTrue(editor.contains("Изменить цену"));
        assertEquals(1, count(editor, "put(box, s, PRICE_INFO"),
                "price editing must be a single button, not a row of controls");
    }

    @Test
    void immediateScreenKeepsSemanticBottomThreeAndTradeInfoOnTheItem() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String immediate = between(controller, "private void renderImmediateQuote", "private void confirmImmediate");
        assertTrue(immediate.contains("Сейчас от"));
        assertTrue(immediate.contains("Сейчас покупают"));
        assertTrue(immediate.contains("Итого"));
        assertTrue(immediate.contains("Получите"));
        assertFalse(immediate.contains("Можно купить"), "liquidity lives on the item tooltip, not in controls");
        assertFalse(immediate.contains("Макс. цена"));
        assertFalse(immediate.contains("Мин. цена"));
        assertFalse(immediate.contains("-10%"));
        assertFalse(immediate.contains("+10%"));
        assertTrue(immediate.contains("put(box, s, TRADE_PRIMARY, button(buy ? MarketIcons.PRIMARY_BUY"));
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

    private static int count(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
    }
}
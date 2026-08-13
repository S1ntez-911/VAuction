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
        assertTrue(commands.contains("Commands.literal(\"admin\")"));
        assertTrue(commands.contains("Commands.literal(\"reloadui\")"));
        assertTrue(commands.contains("source.hasPermission(2)"));
        assertTrue(commands.contains("MarketController.instance().closeAll(source.getServer())"));
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
        assertTrue(commands.contains("draft.expectedInput == TradeDraft.InputTarget.PRICE"));
        assertTrue(commands.contains("CurrencyInput.parse(text)"),
                "price drafts must accept human-readable decimal currency");
        assertTrue(commands.contains("Integer.parseInt(text)"),
                "quantity drafts must remain integer-only");
    }

    @Test
    void sellAndBuyPricesUseTheSharedCurrencyBoundaryParser() throws Exception {
        String commands = source("com/valorcraft/vauction/gui/MarketCommands.java");
        assertTrue(commands.contains("Commands.argument(\"price\", StringArgumentType.word())"));
        assertTrue(commands.contains("Commands.argument(\"maxPrice\", StringArgumentType.word())"));
        assertTrue(commands.contains("sell(ctx.getSource(), StringArgumentType.getString(ctx, \"price\")"));
        assertTrue(commands.contains("StringArgumentType.getString(ctx, \"maxPrice\")"));
        assertFalse(commands.contains("Commands.argument(\"price\", LongArgumentType"));
        assertFalse(commands.contains("Commands.argument(\"maxPrice\", LongArgumentType"));
    }

    @Test
    void publicHelpIsHumanReadableAndHidesTechnicalVocabulary() throws Exception {
        String commands = source("com/valorcraft/vauction/gui/MarketCommands.java");
        String main = between(commands, "private static int help(CommandSourceStack source)",
                "private static int helpCommands");
        assertTrue(main.contains("Как купить:"), "help must explain the game, not the command tree");
        assertTrue(main.contains("Как продать:"));
        assertTrue(main.contains("нажмите на товар"));
        assertTrue(main.contains("«Купить»"));
        assertTrue(main.contains("«Продать»"));
        assertFalse(main.contains("ЛКМ"));
        assertFalse(main.contains("ПКМ"));
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
        String ui = source("com/valorcraft/vauction/gui/UiConfig.java");
        String service = source("com/valorcraft/vauction/application/AuctionService.java");
        assertTrue(controller.contains("\"instant.buyNow\""));
        assertTrue(controller.contains("\"instant.sellNow\""));
        assertTrue(ui.contains("\"instant.ownPrice\""));
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
        assertTrue(controller.contains("UiConfig.slots(layout, \"content\")"));
        assertTrue(controller.contains("read().markets(s.cataloguePage, query, contentSlots.length,"));
        assertTrue(controller.contains("page.totalPages()"));
    }

    @Test
    void guiUsesCleanCardsWhileRealItemIdentityStaysInActions() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String items = source("com/valorcraft/vauction/gui/GuiItems.java");
        assertTrue(controller.contains("GuiItems.marketDisplay(visual"));
        assertTrue(controller.contains("GuiAction.product(visual)"),
                "the exact real stack must stay in the action, separate from its clean display card");
        assertTrue(items.contains("new ItemStack(realItem.getItem())"));
        assertTrue(items.contains("realItem.getHoverName().copy()"));
        assertFalse(controller.contains("GuiItems.decorateMarketItem(visual"),
                "real TFG items add oversized chemical tooltips on the client");
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
        assertTrue(catalogue.contains("uiButton(s, \"my\""), "catalogue must link to «Моё»");
        assertTrue(search.contains("uiButton(s, \"newSearch\""), "search must offer a fresh query");
        assertTrue(search.contains("uiButton(s, \"catalogue\""), "search must offer returning to the whole catalogue");
        assertTrue(search.contains("GuiAction.simple(GuiAction.Type.BROWSE)"));
        assertFalse(search.contains("uiButton(s, \"my\""), "«Моё» is not part of the search task");
        assertTrue(my.contains("uiButton(s, \"allGoods\""));
        assertTrue(my.contains("myInfo"));
    }

    @Test
    void catalogueAndBottomRowsUseOneFixedSpatialGrammar() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String ui = source("com/valorcraft/vauction/gui/UiConfig.java");
        assertTrue(ui.contains("layout(\"catalogue\""));
        assertTrue(ui.contains("layout(\"search\""));
        assertTrue(ui.contains("layout(\"categories\""));
        assertTrue(ui.contains("layout(\"product\""));
        assertTrue(ui.contains("layout(\"immediate\""));
        assertTrue(ui.contains("layout(\"limit\""));
        assertTrue(ui.contains("layout(\"my\""));
        assertTrue(ui.contains("layout(\"manage\""));
        assertTrue(controller.contains("CONFIRM_BACK = 45"));
        assertTrue(controller.contains("CONFIRM_PRIMARY = 49"));
        assertFalse(controller.contains("PRICE_MINUS"), "percentage steppers are gone");
        assertFalse(controller.contains("PRICE_PLUS"));
        assertTrue(controller.contains("uiButton(s, buy ? \"buyNow\" : \"sellNow\""));
        assertTrue(controller.contains("uiButton(s, \"submitLimit\""));
        assertTrue(controller.contains("uiButton(s, \"ownPrice\""), "mode switch on the immediate screen");
        assertFalse(controller.contains("uiButton(s, \"modeNow\""),
                "the limit screen must not add a redundant mode switch");
        assertTrue(controller.contains("UiConfig.slot(\"catalogue\", \"categories\")"));
        assertTrue(controller.contains("UiConfig.button(\"infoBook\")"));
        assertTrue(controller.contains("uiButton(s, \"categories\""));
        assertTrue(controller.contains("UiConfig.slot(\"categories\", \"all\")"));
        assertTrue(controller.contains("UiConfig.slot(\"categories\", \"machines\")"));
    }

    @Test
    void firstOpenAlwaysReceivesFullMenuSyncSoNavigationIsVisibleFromFirstFrame() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        int openBox = controller.indexOf("private void openBox");
        String box = controller.substring(openBox);
        assertTrue(box.contains("player.openMenu"));
        assertTrue(box.contains("screenTitle(s)"),
                "each configurable screen must use its own placeholder-aware title");
        assertTrue(box.contains("if (s.menu != null) fullSync(player, s.menu)"),
                "fresh menu open must push full content immediately");
        assertTrue(box.contains("player.getServer().execute"),
                "fresh menu open must repeat the sync after Forge installs the container");
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
        assertTrue(buy.contains("UiConfig.slot(layout, \"quantityOne\")"));
        assertTrue(buy.contains("UiConfig.slot(layout, \"quantityBulk\")"));
        String sell = controller.substring(elseStart, controller.indexOf("UiConfig.slot(layout, \"quantityOther\")"));
        assertEquals(1, count(sell, "quantityPreset(box, s,"),
                "SELL must offer exactly [1] [Всё]");
        assertTrue(controller.contains("UiConfig.text(\"quantity.all\""));
        assertTrue(controller.contains("UiConfig.slot(layout, \"quantityOther\")"));
        assertTrue(controller.contains("UiConfig.text(\"quantity.other\""));
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
        assertTrue(controller.contains("UiConfig.text(\"draft.command\")"));
        assertFalse(controller.contains("/ah quantity <число>"));
        assertFalse(controller.contains("/ah price <число>"));
        assertTrue(controller.contains("private void beginExactInput"));
        assertTrue(controller.contains("\"draft.quantityMsg\""));
        assertTrue(controller.contains("\"draft.priceMsg\""));
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
        assertTrue(controller.contains("UiConfig.slot(layout, \"quantityBulk\"), 64"));
        String all = between(controller, "private void setMaximumQuantity", "private void renderMarkets");
        assertTrue(all.contains("service().availableCount"), "ALL must read current server inventory on every click");
    }

    @Test
    void catalogueClickOpensExplicitProductChoiceWithoutHiddenMouseModes() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String actions = source("com/valorcraft/vauction/gui/GuiAction.java");
        assertTrue(actions.contains("OPEN_PRODUCT"));
        assertFalse(actions.contains("OPEN_MARKET"));
        assertFalse(controller.contains("button == 1 ? OrderSide.SELL : OrderSide.BUY"));
        assertTrue(controller.contains("private void renderProduct"));
        assertTrue(controller.contains("UiConfig.slot(\"product\", \"buy\")"));
        assertTrue(controller.contains("UiConfig.slot(\"product\", \"sell\")"));
        assertTrue(controller.contains("uiButton(s, \"productSellDisabled\""));
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
        assertTrue(controller.contains("uiButton(s, \"ownPrice\""));
        assertTrue(controller.contains("\"editor.submitBuy\""));
        assertTrue(controller.contains("\"editor.submitSell\""));
        assertTrue(controller.contains("\"editor.submitSummary\""));
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
        assertTrue(editor.contains("\"editor.price\""));
        assertTrue(editor.contains("/ шт."));
        assertTrue(editor.contains("uiButton(s, \"priceInfo\""));
        assertEquals(1, count(editor, "UiConfig.slot(\"limit\", \"price\")"),
                "price editing must be a single button, not a row of controls");
    }

    @Test
    void immediateScreenKeepsSemanticBottomThreeAndTradeInfoOnTheItem() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String immediate = between(controller, "private void renderImmediateQuote", "private void confirmImmediate");
        assertTrue(immediate.contains("\"instant.price\""));
        assertTrue(immediate.contains("\"instant.quantity\""));
        assertTrue(immediate.contains("\"instant.totalBuy\""));
        assertTrue(immediate.contains("\"instant.totalSell\""));
        assertTrue(immediate.contains("\"instant.worstBuy\""));
        assertTrue(immediate.contains("\"instant.worstSell\""));
        assertTrue(immediate.contains("\"instant.partial\""));
        assertTrue(immediate.contains("\"instant.offers\""));
        assertFalse(immediate.contains("Можно купить"), "liquidity lives on the item tooltip, not in controls");
        assertFalse(immediate.contains("Макс. цена"));
        assertFalse(immediate.contains("Мин. цена"));
        assertFalse(immediate.contains("-10%"));
        assertFalse(immediate.contains("+10%"));
        assertTrue(immediate.contains("uiButton(s, buy ? \"buyNow\" : \"sellNow\""));
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
    void catalogueCardsUseActionLabelsAndHumanStatuses() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String ui = source("com/valorcraft/vauction/gui/UiConfig.java");
        assertTrue(controller.contains("\"catalog.buy\""),
                "card price labels must not collide with the action button name");
        assertTrue(controller.contains("\"catalog.sell\""));
        assertTrue(controller.contains("\"catalog.open\""));
        assertTrue(controller.contains("\"product.last\""));
        assertTrue(controller.contains("\"product.available\""));
        assertFalse(controller.contains("labelValue(\"Купить сейчас\""));
        assertFalse(controller.contains("labelValue(\"Продать сейчас\""));
        assertTrue(ui.contains("my.waitSell"));
        assertTrue(ui.contains("my.waitBuy"));
        assertTrue(ui.contains("my.partial"));
        assertTrue(ui.contains("my.manual"));
        assertFalse(controller.contains("Ожидает продавца"), "statuses must sound human, not exchange-like");
        assertFalse(controller.contains("Ожидает покупателя"));
        assertFalse(ui.contains("Ожидает продавца"));
        assertFalse(ui.contains("Ожидает покупателя"));
    }

    @Test
    void russianSearchExpandsIntoBoundedEnglishAliasGroups() throws Exception {
        String read = source("com/valorcraft/vauction/application/AuctionReadService.java");
        String repository = source("com/valorcraft/vauction/persistence/MarketReadRepository.java");
        String vocabulary = source("com/valorcraft/vauction/item/SearchVocabulary.java");
        assertTrue(read.contains("SearchVocabulary.groups(query)"));
        assertTrue(repository.contains("item_search_name LIKE ? ESCAPE '\\\\'"));
        assertTrue(repository.contains(" OR "), "aliases are OR-ed inside a word group");
        assertTrue(repository.contains(" AND "), "word groups are AND-ed together");
        assertTrue(vocabulary.contains("MAX_GROUPS"), "alias expansion must stay bounded");
        assertTrue(vocabulary.contains("MAX_ALIASES"));
        assertTrue(vocabulary.contains("\"медь\""));
        assertTrue(vocabulary.contains("\"copper\""));
        assertTrue(vocabulary.contains("\"слиток\""));
        assertTrue(vocabulary.contains("\"ingot\""));
        assertTrue(vocabulary.contains("\"руда\""));
        assertTrue(vocabulary.contains("\"ore\""));
    }

    @Test
    void feedbackUsesServerSideActionBarAndSeparatorBeforeNativeTooltip() throws Exception {
        String controller = source("com/valorcraft/vauction/gui/MarketController.java");
        String ui = source("com/valorcraft/vauction/gui/UiConfig.java");
        String items = source("com/valorcraft/vauction/gui/GuiItems.java");
        String text = source("com/valorcraft/vauction/gui/MarketText.java");
        String palette = source("com/valorcraft/vauction/gui/MarketPalette.java");
        String sounds = source("com/valorcraft/vauction/gui/MarketSounds.java");
        assertTrue(text.contains("static void bar(ServerPlayer player, String text, TextColor color)"));
        assertTrue(text.contains("displayClientMessage"));
        assertTrue(text.contains("withItalic(false)"),
                "exchange lore must be explicitly non-italic so the client renders it straight");
        assertTrue(text.contains("divider()"));
        assertTrue(text.contains("────────"));
        assertTrue(palette.contains("\"separator\""), "separator colour key must be palette-configurable");
        assertTrue(items.contains("MarketText.divider()"), "decorator must close the exchange block with a separator");
        assertTrue(items.indexOf("for (Component line : marketLines)")
                        < items.indexOf("MarketText.divider()"),
                "separator line goes after the last exchange line");
        assertTrue(ui.contains("\"bar.bought\", \"Куплено: {q} шт. за {a}\""));
        assertTrue(ui.contains("\"bar.sold\", \"Продано: {q} шт. за {a}\""));
        assertTrue(ui.contains("bar.orderCreated"));
        assertTrue(ui.contains("bar.orderFilled"));
        assertTrue(ui.contains("bar.orderPending"));
        assertTrue(ui.contains("\"bar.orderPartialBuy\", \"Куплено: {q} · осталось {r}\""),
                "partial fill bar must report the remaining quantity");
        assertTrue(ui.contains("\"bar.orderPartialSell\", \"Продано: {q} · осталось {r}\""));
        assertFalse(controller.contains("Моём».\")))"), "cancel-confirm must keep exactly one short line");
        assertTrue(ui.contains("\"cancel.body\", \"Остаток вернётся в «Моё».\""));
        assertTrue(controller.contains("UiConfig.fmt(s.orderSide == OrderSide.BUY"),
                "partial fill bar is chosen by order side");
        assertTrue(controller.contains("bar.orderPartialSell\""));
        assertTrue(ui.contains("Деньги резервируются"));
        assertFalse(ui.contains("Спишется позже"));
        assertTrue(ui.contains("bar.claim"));
        assertTrue(ui.contains("bar.cancelled"));
        assertTrue(ui.contains("bar.offersGone"));
        assertTrue(ui.contains("bar.noMoney"));
        assertTrue(ui.contains("bar.noItems"));
        assertTrue(ui.contains("bar.failed"));
        assertTrue(sounds.contains("static void placed("), "calm placed-sound distinct from instant-trade success");
        assertTrue(controller.contains("MarketSounds.placed(player)"));
        assertTrue(controller.contains("tradeAmount(outcome.trades(), true)"), "buy total = buyer-paid gross");
        assertTrue(controller.contains("tradeAmount(outcome.trades(), false)"), "sell total = seller net");
        assertTrue(controller.contains("t.sellerNet()"));
    }

    @Test
    void moneyReasonsAreUserFacingAndNeverLeakEscrowInternals() throws Exception {
        String service = source("com/valorcraft/vauction/application/AuctionService.java");
        String recovery = source("com/valorcraft/vauction/recovery/RecoveryService.java");
        String snapshot = source("com/valorcraft/vauction/item/ItemSnapshot.java");
        assertTrue(service.contains("\"Заявка на покупку\""), "fresh buy order reserve must be user-facing");
        assertTrue(service.contains("\"Покупка на бирже\""), "instant buy reserve must be user-facing");
        assertTrue(service.contains("\"Сделка на бирже: \""), "settlement must read as a trade");
        assertTrue(service.contains("\"Возврат заявки: \""), "cancel refund must be user-facing");
        assertTrue(service.contains("\"Возврат по истечении заявки: \""), "expiry refund must be user-facing");
        assertTrue(recovery.contains("\"Заявка на покупку: \""),
                "recovery re-reserve must reuse the same human reason as placement");
        assertTrue(snapshot.contains("displayLabel()"), "human item label lives on the snapshot");
        assertFalse(service.contains("buy hold "), "technical reserve wording is gone");
        assertFalse(service.contains("settle+rollover "), "technical settlement wording is gone");
        assertFalse(recovery.contains("\"recovery "), "technical recovery wording is gone");
        assertFalse(service.contains("\"ESCROW"), "ESCROW vocabulary must not reach the player");
        assertTrue(service.contains("\"va:buy:\"") && service.contains("\"va:rollover:\""),
                "idempotency keys stay technical and separate from the visible reason");
        assertTrue(service.contains("\"va:\" + verb + \":\""),
                "cancel/expire idempotency keys stay technical and separate from the visible reason");
    }

    private static int count(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
    }
}

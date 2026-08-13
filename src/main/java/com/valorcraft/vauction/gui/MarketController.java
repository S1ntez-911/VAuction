package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.application.AuctionReadService;
import com.valorcraft.vauction.application.AuctionService;
import com.valorcraft.vauction.application.Page;
import com.valorcraft.vauction.application.PlayerMarketActivity;
import com.valorcraft.vauction.bootstrap.VAuctionCore;
import com.valorcraft.vauction.domain.market.MarketCard;
import com.valorcraft.vauction.domain.market.MarketSummary;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderStatus;
import com.valorcraft.vauction.domain.trade.Trade;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned navigation and action mapping for vanilla inventory screens. */
public final class MarketController {
    private static final Logger LOGGER = LogManager.getLogger("VAuction");
    /** Confirmation screens remain fixed deliberately; normal screens use UiConfig layouts. */
    static final int CONFIRM_BACK = 45;
    static final int CONFIRM_PRIMARY = 49;
    private static final MarketController INSTANCE = new MarketController();

    private final Map<UUID, MarketSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, TradeDraft> drafts = new ConcurrentHashMap<>();

    private MarketController() {}

    public static MarketController instance() {
        return INSTANCE;
    }

    public void open(ServerPlayer player) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.BROWSE;
        session.cataloguePage = 0;
        session.searchActive = false;
        session.search = "";
        session.filter = MarketFilter.ALL;
        if (VAuctionCore.instance().notificationService().firstMarketOpen(player.getUUID())) {
            onboarding(player);
        }
        renderMarkets(player, session);
    }

    public void search(ServerPlayer player, String query, int page) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.SEARCH;
        session.searchActive = true;
        session.search = query == null ? "" : query.trim();
        session.filter = MarketFilter.ALL;
        session.cataloguePage = Math.max(0, page);
        renderMarkets(player, session);
    }

    public void openOrders(ServerPlayer player) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.MY;
        session.page = 0;
        renderMy(player, session);
    }

    public void openDeliveries(ServerPlayer player) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.MY;
        session.page = 0;
        renderMy(player, session);
    }

    /**
     * Contextual «/ah set <число>» input. Applies the value to the active input
     * draft (QUANTITY or PRICE) and re-opens the same trade screen. The command
     * literal itself is hidden by {@code .requires} while no draft exists, so an
     * average player never meets it outside the GUI-guided input flow.
     */
    public boolean setExact(ServerPlayer player, long value) {
        if (value <= 0) return false;
        TradeDraft draft = drafts.get(player.getUUID());
        if (draft != null && !draft.expired()) {
            if (draft.expectedInput == TradeDraft.InputTarget.PRICE) {
                draft.price = value;
                draft.immediate = false;
            } else {
                draft.quantity = (int) Math.min(value, Integer.MAX_VALUE);
            }
            reopenFromDraft(player, draft);
            return true;
        }
        return false;
    }

    public boolean hasInputDraft(UUID playerId) {
        TradeDraft draft = drafts.get(playerId);
        return draft != null && !draft.expired();
    }

    TradeDraft inputDraft(UUID playerId) {
        TradeDraft draft = drafts.get(playerId);
        return draft == null || draft.expired() ? null : draft;
    }

    public void clicked(Player rawPlayer, MarketSession session, int slotId,
                        int button, ClickType clickType) {
        if (!(rawPlayer instanceof ServerPlayer player)
                || !player.getUUID().equals(session.playerId) || session.executing) return;
        // Only ordinary left/right pickup clicks can invoke actions. QUICK_MOVE, SWAP,
        // CLONE, THROW, QUICK_CRAFT and PICKUP_ALL are all rejected without mutation.
        if (clickType != ClickType.PICKUP || (button != 0 && button != 1)) return;
        if (slotId < 0 || session.contents == null || slotId >= session.contents.getContainerSize()) return;
        GuiAction action = session.actions.get(slotId);
        if (action == null) return;
        session.executing = true;
        try {
            if (action.type() == GuiAction.Type.OPEN_PRODUCT) {
                MarketSounds.navigation(player);
                openProduct(player, session, action.item());
            } else {
                handle(player, session, action);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Market GUI action failed: player={}, screen={}, action={}",
                    player.getUUID(), session.screen, action.type(), e);
            tell(player, UiConfig.text("chat.guiDown"), ChatFormatting.RED);
            MarketSounds.error(player);
        } finally {
            session.executing = false;
        }
    }

    public void closed(UUID playerId, int containerId) {
        MarketSession session = sessions.get(playerId);
        if (session != null && !session.transitioning && session.containerId == containerId) {
            sessions.remove(playerId, session);
        }
    }

    public void logout(UUID playerId) {
        sessions.remove(playerId);
        drafts.remove(playerId);
    }

    public void clear() {
        sessions.clear();
        drafts.clear();
    }

    /** Closes every live auction menu before publishing a reloaded layout. */
    public void closeAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof ServerChestMenu) player.closeContainer();
        }
        clear();
    }

    private void handle(ServerPlayer player, MarketSession s, GuiAction a) {
        if (a.type() == GuiAction.Type.HOME || a.type() == GuiAction.Type.BROWSE
                || a.type() == GuiAction.Type.MY || a.type() == GuiAction.Type.BACK) {
            MarketSounds.navigation(player);
        }
        switch (a.type()) {
            case HOME -> { s.screen = MarketScreen.BROWSE; s.cataloguePage = 0; s.search = ""; s.searchActive = false; s.filter = MarketFilter.ALL;
                s.orderSide = null; s.inventorySlot = -1; renderMarkets(player, s); }
            case BROWSE -> { s.screen = MarketScreen.BROWSE; s.cataloguePage = 0; s.search = ""; s.searchActive = false; s.filter = MarketFilter.ALL; renderMarkets(player, s); }
            case REFRESH -> { MarketSounds.page(player); renderMarkets(player, s); }
            case HELP -> tutorial(player);
            case SEARCH_HELP -> searchHelp(player);
            case OPEN_FILTERS -> { MarketSounds.navigation(player); renderCategories(player, s); }
            case MY -> { s.screen = MarketScreen.MY; s.page = 0; renderMy(player, s); }
            case PAGE -> { if (s.screen == MarketScreen.BROWSE || s.screen == MarketScreen.SEARCH) {
                    s.cataloguePage = Math.max(0, s.cataloguePage + a.number());
                } else s.page = Math.max(0, s.page + a.number());
                MarketSounds.page(player); refreshCurrent(player, s); }
            case FILTER -> { s.filter = MarketFilter.byOrdinal(a.number()); s.cataloguePage = 0;
                s.searchActive = false; s.search = ""; MarketSounds.mode(player); renderMarkets(player, s); }
            case BUY -> { MarketSounds.mode(player); beginSelectedSide(player, s, OrderSide.BUY); }
            case SELL -> { MarketSounds.mode(player); beginSelectedSide(player, s, OrderSide.SELL); }
            case BUY_NOW -> { MarketSounds.mode(player); beginImmediate(player, s, OrderSide.BUY); }
            case SELL_NOW -> { MarketSounds.mode(player); beginImmediate(player, s, OrderSide.SELL); }
            case SET_QUANTITY -> setQuantityPreset(player, s, a.number());
            case SET_MAX_QUANTITY -> setMaximumQuantity(player, s);
            case REVIEW -> reviewOrSubmit(player, s);
            case CONFIRM_IMMEDIATE -> confirmImmediate(player, s);
            case CONFIRM_ORDER -> confirmOrder(player, s);
            case MANAGE_ORDER -> manageOrder(player, s, a);
            case PREPARE_CANCEL -> { s.screen = MarketScreen.CONFIRM_CANCEL; renderCancel(player, s); }
            case EXACT_QUANTITY -> beginExactInput(player, s, TradeDraft.InputTarget.QUANTITY);
            case EXACT_PRICE -> beginExactInput(player, s, TradeDraft.InputTarget.PRICE);
            case CONFIRM_CANCEL -> confirmCancel(player, s);
            case CLAIM -> claim(player, s, a.deliveryId());
            case BACK -> back(player, s);
        }
    }

    private void setQuantityPreset(ServerPlayer player, MarketSession s, int preset) {
        if (s.orderSide == OrderSide.SELL) {
            int available = service().availableCount(player.getUUID(), s.unit);
            if (available <= 0) {
                MarketText.bar(player, UiConfig.text("bar.noItems"), MarketPalette.byKey("error"));
                MarketSounds.error(player);
                return;
            }
            s.quantity = MarketQuantity.sellPreset(preset, available);
        } else {
            s.quantity = MarketQuantity.buyPreset(preset);
        }
        MarketSounds.preset(player, false);
        if (s.immediate) renderImmediateQuote(player, s); else renderEditor(player, s);
    }

    private void setMaximumQuantity(ServerPlayer player, MarketSession s) {
        int available = service().availableCount(player.getUUID(), s.unit);
        if (available <= 0) {
            MarketText.bar(player, UiConfig.text("bar.noItems"), MarketPalette.byKey("error"));
            MarketSounds.error(player);
            return;
        }
        s.quantity = MarketQuantity.sellAll(available);
        MarketSounds.preset(player, true);
        if (s.immediate) renderImmediateQuote(player, s); else renderEditor(player, s);
    }

    /**
     * «Другое» / «Изменить цену»: saves a timed draft and guides the player to
     * the single contextual command {@code /ah set <число>} without losing the
     * open trade. The command reads the draft target and applies the right field.
     */
    private void beginExactInput(ServerPlayer player, MarketSession s, TradeDraft.InputTarget target) {
        drafts.put(player.getUUID(), TradeDraft.of(s, target));
        player.sendSystemMessage(MarketText.brand());
        player.sendSystemMessage(MarketText.text(UiConfig.text(target == TradeDraft.InputTarget.PRICE
                ? "draft.priceMsg" : "draft.quantityMsg")));
        player.sendSystemMessage(Component.literal(UiConfig.text("draft.command"))
                .withStyle(style -> style.withColor(MarketPalette.byKey("info"))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/ah set "))));
        player.sendSystemMessage(MarketText.muted(UiConfig.text(target == TradeDraft.InputTarget.PRICE
                ? "draft.priceNote" : "draft.quantityNote")));
        MarketSounds.preset(player, false);
    }

    /** Re-opens the trade from a draft after /ah set ran while the GUI was closed. */
    private void reopenFromDraft(ServerPlayer player, TradeDraft draft) {
        String key = read().marketKey(draft.unit);
        if (key == null) {
            drafts.remove(player.getUUID());
            tell(player, UiConfig.text("chat.noMarketDraft"), ChatFormatting.RED);
            return;
        }
        if (draft.side == OrderSide.SELL
                && service().availableCount(player.getUUID(), draft.unit) <= 0) {
            drafts.remove(player.getUUID());
            tell(player, UiConfig.text("chat.noItemDraft"), ChatFormatting.RED);
            return;
        }
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.TRADE_IMMEDIATE;
        session.searchActive = draft.searchActive;
        session.search = draft.search;
        session.cataloguePage = draft.page;
        session.unit = draft.unit.copy();
        session.marketKey = key;
        session.inventorySlot = -1;
        session.orderSide = draft.side;
        session.immediate = draft.immediate;
        session.quantity = draft.quantity;
        session.price = draft.price;
        session.pendingRequestId = UUID.randomUUID();
        drafts.remove(player.getUUID());
        if (draft.immediate) {
            renderImmediateQuote(player, session);
        } else {
            session.screen = MarketScreen.TRADE_LIMIT;
            renderEditor(player, session);
        }
    }

    private void renderMarkets(ServerPlayer player, MarketSession s) {
        s.screen = s.searchActive ? MarketScreen.SEARCH : MarketScreen.BROWSE;
        String layout = s.screen == MarketScreen.SEARCH ? "search" : "catalogue";
        int[] contentSlots = UiConfig.slots(layout, "content");
        String query = s.searchActive ? s.search : "";
        Page<MarketCard> page = read().markets(s.cataloguePage, query, contentSlots.length,
                s.searchActive ? null : s.filter.category);
        s.cataloguePage = page.page();
        context(player, s,
                "category", UiConfig.text(s.filter == MarketFilter.ALL ? "filter.all" : s.filter.textKey),
                "search", s.search,
                "page", page.page() + 1,
                "pages", page.totalPages(),
                "results", page.items().size());
        SimpleContainer box = blank(s);
        s.resetActions();
        int i = 0;
        for (MarketCard card : page.items()) {
            ItemStack visual = read().visual(card.visual());
            if (visual.isEmpty()) visual = new ItemStack(Items.BARRIER);
            MarketSummary m = card.summary();
            ItemStack icon = GuiItems.marketDisplay(visual, cardLore(m));
            put(box, s, contentSlots[i++], icon, GuiAction.product(visual));
        }
        if (page.items().isEmpty() && s.screen == MarketScreen.SEARCH) {
            tell(player, UiConfig.fmt("chat.searchEmpty", "q", s.search), ChatFormatting.YELLOW);
            set(box, UiConfig.slot(layout, "empty"), uiButton(s, "emptySearch",
                    MarketText.action(UiConfig.text("empty.searchTitle"), MarketPalette.byKey("warning")),
                    List.of(MarketText.muted(UiConfig.text("empty.searchBody")),
                            MarketText.muted(UiConfig.text("empty.createFirst")),
                            MarketText.muted(UiConfig.text("empty.sellBuy")))));
        } else if (page.items().isEmpty() && s.filter != MarketFilter.ALL) {
            set(box, UiConfig.slot(layout, "empty"), uiButton(s, "emptyFilter",
                    MarketText.text(UiConfig.text("empty.filterTitle")), List.of(
                            MarketText.muted(UiConfig.text("empty.filterBody")))));
        } else if (page.items().isEmpty()) {
            set(box, UiConfig.slot(layout, "empty"), uiButton(s, "emptyCatalogue",
                    MarketText.text(UiConfig.text("empty.catalogTitle")), List.of(
                            MarketText.muted(UiConfig.text("empty.createFirst")),
                            MarketText.muted(UiConfig.text("empty.sellBuy")))));
        }
        if (s.screen == MarketScreen.SEARCH) searchNavigation(box, s, page);
        else catalogueNavigation(box, s, page);
        openBox(player, s, box);
    }

    private void openProduct(ServerPlayer player, MarketSession s, ItemStack unit) {
        unit = unit.copy();
        unit.setCount(1);
        String key = read().marketKey(unit);
        if (key == null) {
            tell(player, UiConfig.text("chat.noMarket"), ChatFormatting.RED);
            return;
        }
        s.unit = unit;
        s.marketKey = key;
        s.inventorySlot = -1;
        renderProduct(player, s);
    }

    private void renderProduct(ServerPlayer player, MarketSession s) {
        s.screen = MarketScreen.PRODUCT;
        SimpleContainer box = blank(s);
        s.resetActions();
        AuctionReadService.MarketView view = read().market(s.unit);
        MarketSummary summary = view == null ? null : view.card().summary();
        long ask = summary == null ? 0 : summary.bestAsk();
        long bid = summary == null ? 0 : summary.bestBid();
        long last = summary == null ? 0 : summary.lastTradePrice();
        int available = service().availableCount(player.getUUID(), s.unit);
        context(player, s,
                "item", s.unit.getHoverName().getString(),
                "available", available,
                "buy_price", moneyOrUnavailable(ask),
                "sell_price", moneyOrUnavailable(bid),
                "last_price", moneyOrDash(last));

        LinkedHashMap<String, UiConfig.LineValue> values = new LinkedHashMap<>();
        values.put("product.title", new UiConfig.LineValue(null,
                UiConfig.text("product.title"), "brand"));
        values.put("product.buy", new UiConfig.LineValue("product.buy",
                moneyOrUnavailable(ask), ask > 0 ? "success" : "muted"));
        values.put("product.sell", new UiConfig.LineValue("product.sell",
                moneyOrUnavailable(bid), bid > 0 ? "sell" : "muted"));
        values.put("product.last", new UiConfig.LineValue("product.last",
                moneyOrDash(last), "text"));
        values.put("product.available", new UiConfig.LineValue("product.available",
                Integer.toString(available), available > 0 ? "text" : "muted"));
        set(box, UiConfig.slot("product", "item"),
                GuiItems.marketDisplay(s.unit, UiConfig.lines("product", values, s.placeholders)));

        put(box, s, UiConfig.slot("product", "back"), uiButton(s, "back", null, null), GuiAction.simple(GuiAction.Type.BACK));
        String buyName = UiConfig.text(ask > 0 ? "product.buyNow" : "product.buyOrder");
        String buyLore = ask > 0
                ? UiConfig.fmt("product.nowAt", "price", CurrencyText.format(ask))
                : UiConfig.text("product.orderLore");
        put(box, s, UiConfig.slot("product", "buy"), uiButton(s, "productBuy",
                MarketText.action(buyName, MarketPalette.byKey("success")),
                List.of(MarketText.muted(buyLore))), GuiAction.simple(GuiAction.Type.BUY));

        if (available > 0) {
            String sellName = UiConfig.text(bid > 0 ? "product.sellNow" : "product.sellOrder");
            String sellLore = bid > 0
                    ? UiConfig.fmt("product.nowAt", "price", CurrencyText.format(bid))
                    : UiConfig.text("product.orderLore");
            put(box, s, UiConfig.slot("product", "sell"), uiButton(s, "productSell",
                    MarketText.action(sellName, MarketPalette.byKey("sell")),
                    List.of(MarketText.muted(sellLore))), GuiAction.simple(GuiAction.Type.SELL));
        } else {
            put(box, s, UiConfig.slot("product", "sell"), uiButton(s, "productSellDisabled", null, null), null);
        }
        openBox(player, s, box);
    }

    private void beginSelectedSide(ServerPlayer player, MarketSession s, OrderSide side) {
        AuctionReadService.MarketView view = read().market(s.unit);
        MarketSummary summary = view == null ? null : view.card().summary();
        long executablePrice = summary == null ? 0
                : side == OrderSide.BUY ? summary.bestAsk() : summary.bestBid();
        if (executablePrice > 0) beginImmediate(player, s, side);
        else beginOrder(player, s, side);
    }

    private void beginImmediate(ServerPlayer player, MarketSession s, OrderSide side) {
        boolean switchingMode = s.screen == MarketScreen.TRADE_LIMIT || s.screen == MarketScreen.PRICE_WARNING;
        int available = side == OrderSide.SELL ? service().availableCount(player.getUUID(), s.unit) : 0;
        if (side == OrderSide.SELL && available <= 0) {
            tell(player, UiConfig.text("chat.noItemFull"), ChatFormatting.RED);
            return;
        }
        s.orderSide = side;
        s.immediate = true;
        s.quantity = switchingMode
                ? (side == OrderSide.SELL ? MarketQuantity.sellPreset(s.quantity, available)
                : MarketQuantity.buyPreset(s.quantity))
                : (side == OrderSide.SELL ? Math.min(64, available) : 1);
        s.pendingRequestId = UUID.randomUUID();
        s.screen = MarketScreen.TRADE_IMMEDIATE;
        renderImmediateQuote(player, s);
    }

    private void renderImmediateQuote(ServerPlayer player, MarketSession s) {
        int available = s.orderSide == OrderSide.SELL
                ? service().availableCount(player.getUUID(), s.unit) : Integer.MAX_VALUE;
        if (s.orderSide == OrderSide.SELL) s.quantity = Math.min(s.quantity, Math.max(1, available));
        AuctionReadService.ImmediateQuote quote = s.orderSide == OrderSide.BUY
                ? read().quoteBuyNow(s.unit, s.quantity, player.getUUID())
                : read().quoteSellNow(s.unit, s.quantity, player.getUUID());
        s.quote = quote;
        s.screen = MarketScreen.TRADE_IMMEDIATE;
        SimpleContainer box = blank(s);
        s.resetActions();
        boolean buy = s.orderSide == OrderSide.BUY;
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        AuctionReadService.MarketView market = read().market(s.unit);
        MarketSummary summary = market == null ? null : market.card().summary();
        long shownPrice = summary == null ? 0 : (buy ? summary.bestAsk() : summary.bestBid());
        context(player, s,
                "item", s.unit.getHoverName().getString(),
                "side", UiConfig.text(buy ? "my.purchase" : "my.sell"),
                "quantity", quote.requestedQuantity(),
                "requested", quote.requestedQuantity(),
                "fillable", quote.fillableQuantity(),
                "price", moneyOrUnavailable(shownPrice),
                "total", quote.executable() ? CurrencyText.format(quote.expectedTotal()) : UiConfig.text("card.dash"),
                "worst_price", quote.executable() ? CurrencyText.format(quote.worstExecutionPrice()) : UiConfig.text("card.dash"),
                "available", available == Integer.MAX_VALUE ? "" : available);
        LinkedHashMap<String, UiConfig.LineValue> v = new LinkedHashMap<>();
        v.put("instant.action", new UiConfig.LineValue(null,
                UiConfig.text(buy ? "instant.actionBuy" : "instant.actionSell"), buy ? "success" : "sell"));
        v.put("instant.price", new UiConfig.LineValue("instant.price",
                moneyOrUnavailable(summary == null ? 0 : (buy
                        ? summary.bestAsk() : summary.bestBid())), "text"));
        v.put("instant.quantity", new UiConfig.LineValue("instant.quantity",
                Integer.toString(quote.requestedQuantity()), "text"));
        if (quote.fillableQuantity() < quote.requestedQuantity()) {
            v.put("instant.partial", new UiConfig.LineValue("instant.partial",
                    quote.fillableQuantity() + " из " + quote.requestedQuantity(), "warning"));
        }
        if (quote.executable()) {
            v.put("instant.total", new UiConfig.LineValue(
                    buy ? "instant.totalBuy" : "instant.totalSell",
                    CurrencyText.format(quote.expectedTotal()), "text"));
            v.put("instant.worst", new UiConfig.LineValue(
                    buy ? "instant.worstBuy" : "instant.worstSell",
                    CurrencyText.format(quote.worstExecutionPrice()) + " / шт.", "text"));
        } else {
            v.put("instant.offers", new UiConfig.LineValue(null, UiConfig.text("instant.offers"), "warning"));
        }
        set(box, UiConfig.slot("immediate", "item"),
                GuiItems.marketDisplay(s.unit, UiConfig.lines("tradeNow", v, s.placeholders)));
        quantityControls(box, s, s.orderSide, "immediate");
        put(box, s, UiConfig.slot("immediate", "back"), uiButton(s, "back", null, null), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, UiConfig.slot("immediate", "ownPrice"), uiButton(s, "ownPrice", null, null),
                GuiAction.simple(buy ? GuiAction.Type.BUY : GuiAction.Type.SELL));
        if (quote.executable()) {
            put(box, s, UiConfig.slot("immediate", "confirm"), uiButton(s, buy ? "buyNow" : "sellNow",
                    MarketText.action(UiConfig.text(buy ? "instant.buyNow" : "instant.sellNow"),
                            MarketPalette.byKey(buy ? "success" : "sell")),
                    List.of(MarketText.muted(UiConfig.text(buy ? "instant.buyNowLore" : "instant.sellNowLore")))),
                    GuiAction.simple(GuiAction.Type.CONFIRM_IMMEDIATE));
        } else {
            put(box, s, UiConfig.slot("immediate", "confirm"), uiButton(s, "disabledOffers", null, null), null);
        }
        openBox(player, s, box);
    }

    private void confirmImmediate(ServerPlayer player, MarketSession s) {
        AuctionReadService.ImmediateQuote quote = s.quote;
        if (quote == null || !quote.executable() || s.pendingRequestId == null) return;
        if (s.orderSide == OrderSide.BUY) {
            long reserve;
            try {
                reserve = Math.multiplyExact(quote.worstExecutionPrice(), (long) quote.fillableQuantity());
            } catch (ArithmeticException e) {
                tell(player, UiConfig.text("chat.immediateTooBig"), ChatFormatting.RED);
                return;
            }
            long balance = VAuctionCore.instance().economyGateway().getBalance(player.getUUID());
            if (balance < reserve) {
                MarketText.bar(player, UiConfig.text("bar.noMoney"), MarketPalette.byKey("error"));
                tell(player, UiConfig.fmt("chat.buyNeeds", "need", CurrencyText.format(reserve),
                        "have", CurrencyText.format(balance)), ChatFormatting.RED);
                return;
            }
        }
        AuctionService.Outcome outcome = s.orderSide == OrderSide.BUY
                ? service().executeBuyNow(player.getUUID(), s.unit, quote.worstExecutionPrice(),
                        quote.fillableQuantity(), s.pendingRequestId)
                : service().executeSellNow(player.getUUID(), s.unit, quote.worstExecutionPrice(),
                        quote.fillableQuantity(), s.pendingRequestId);
        long filled = outcome.filledQuantity();
        boolean buy = s.orderSide == OrderSide.BUY;
        if (outcome.isSuccess()) MarketSounds.success(player); else MarketSounds.error(player);
        if (!outcome.isSuccess()) {
            MarketText.bar(player, UiConfig.text("bar.failed"), MarketPalette.byKey("error"));
            showOutcome(player, outcome);
        } else if (filled == 0) {
            MarketText.bar(player, UiConfig.text("bar.offersGone"), MarketPalette.byKey("warning"));
            tell(player, UiConfig.text(buy ? "chat.routeChangedBuy" : "chat.routeChangedSell"),
                    ChatFormatting.YELLOW);
        } else if (buy) {
            MarketText.bar(player, UiConfig.fmt("bar.bought", "q", filled,
                    "a", CurrencyText.format(tradeAmount(outcome.trades(), true))), MarketPalette.byKey("success"));
            tell(player, UiConfig.fmt("chat.boughtPlace", "item",
                    s.unit.getHoverName().getString()), ChatFormatting.GREEN);
        } else {
            MarketText.bar(player, UiConfig.fmt("bar.sold", "q", filled,
                    "a", CurrencyText.format(tradeAmount(outcome.trades(), false))), MarketPalette.byKey("success"));
            if (filled < quote.fillableQuantity()) {
                tell(player, UiConfig.text("chat.soldLeft"), ChatFormatting.GREEN);
            }
        }
        s.pendingRequestId = null;
        s.immediate = false;
        if (s.orderSide == OrderSide.BUY || filled < quote.fillableQuantity()) {
            s.screen = MarketScreen.MY;
            s.page = 0;
            renderMy(player, s);
        } else {
            returnToCatalogue(player, s);
        }
    }

    private void beginOrder(ServerPlayer player, MarketSession s, OrderSide side) {
        int available = side == OrderSide.SELL ? service().availableCount(player.getUUID(), s.unit) : 0;
        if (side == OrderSide.SELL && available <= 0) {
            tell(player, UiConfig.text("chat.noItem"), ChatFormatting.RED);
            return;
        }
        s.orderSide = side;
        s.immediate = false;
        s.quantity = side == OrderSide.SELL
                ? MarketQuantity.sellPreset(s.quantity, available)
                : MarketQuantity.buyPreset(s.quantity);
        AuctionReadService.MarketView view = read().market(s.unit);
        MarketSummary m = view == null ? null : view.card().summary();
        long preferred = side == OrderSide.BUY
                ? (m == null ? 0 : m.bestAsk()) : (m == null ? 0 : m.bestBid());
        if (preferred <= 0 && m != null) preferred = m.lastTradePrice();
        s.price = Math.max(1, preferred);
        s.pendingRequestId = UUID.randomUUID();
        s.screen = MarketScreen.TRADE_LIMIT;
        renderEditor(player, s);
    }

    private void renderEditor(ServerPlayer player, MarketSession s) {
        s.screen = MarketScreen.TRADE_LIMIT;
        boolean buy = s.orderSide == OrderSide.BUY;
        SimpleContainer box = blank(s);
        s.resetActions();
        long total;
        try { total = Math.multiplyExact(s.price, (long) s.quantity); }
        catch (ArithmeticException ignored) { total = Long.MAX_VALUE; }
        int available = s.orderSide == OrderSide.SELL
                ? service().availableCount(player.getUUID(), s.unit) : 0;
        context(player, s,
                "item", s.unit.getHoverName().getString(),
                "side", UiConfig.text(buy ? "my.purchase" : "my.sell"),
                "quantity", s.quantity,
                "price", CurrencyText.format(s.price),
                "total", CurrencyText.format(total),
                "available", s.orderSide == OrderSide.SELL ? available : "");
        LinkedHashMap<String, UiConfig.LineValue> v = new LinkedHashMap<>();
        v.put("editor.action", new UiConfig.LineValue(null,
                UiConfig.text(s.orderSide == OrderSide.BUY ? "editor.actionBuy" : "editor.actionSell"),
                s.orderSide == OrderSide.BUY ? "success" : "sell"));
        v.put("editor.quantity", new UiConfig.LineValue("editor.quantity", Integer.toString(s.quantity), "text"));
        v.put("editor.price", new UiConfig.LineValue("editor.price",
                CurrencyText.format(s.price) + " / шт.", "text"));
        v.put("editor.reserve", new UiConfig.LineValue(s.orderSide == OrderSide.BUY ? "editor.reserve" : "editor.sum",
                CurrencyText.format(total), "text"));
        if (s.orderSide == OrderSide.SELL) {
            v.put("editor.available", new UiConfig.LineValue("editor.available",
                    Integer.toString(available), "text"));
        } else {
            v.put("editor.reserveNote", new UiConfig.LineValue(null,
                    UiConfig.text("editor.reserveNote"), "muted"));
        }
        set(box, UiConfig.slot("limit", "item"),
                GuiItems.marketDisplay(s.unit, UiConfig.lines("tradeLimit", v, s.placeholders)));
        quantityControls(box, s, s.orderSide, "limit");
        put(box, s, UiConfig.slot("limit", "price"), uiButton(s, "priceInfo",
                        MarketText.action(UiConfig.fmt("editor.priceButton", "price", CurrencyText.format(s.price)),
                                MarketPalette.byKey("info")),
                        List.of(MarketText.muted(UiConfig.text("editor.priceButtonLore")))),
                GuiAction.simple(GuiAction.Type.EXACT_PRICE));
        put(box, s, UiConfig.slot("limit", "back"), uiButton(s, "back", null, null), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, UiConfig.slot("limit", "submit"), uiButton(s, "submitLimit",
                MarketText.action(UiConfig.text(buy ? "editor.submitBuy" : "editor.submitSell"),
                        buy ? MarketPalette.byKey("success") : MarketPalette.byKey("sell")),
                List.of(MarketText.muted(UiConfig.fmt("editor.submitSummary",
                        "quantity", s.quantity, "price", CurrencyText.format(s.price),
                        "total", CurrencyText.format(total))))),
                GuiAction.simple(GuiAction.Type.REVIEW));
        openBox(player, s, box);
    }

    private void reviewOrSubmit(ServerPlayer player, MarketSession s) {
        try {
            Math.multiplyExact(s.price, (long) s.quantity);
        } catch (ArithmeticException e) {
            tell(player, UiConfig.text("chat.orderTooBig"), ChatFormatting.RED);
            return;
        }
        AuctionReadService.MarketView currentView = read().market(s.unit);
        MarketSummary currentMarket = currentView == null ? null : currentView.card().summary();
        boolean warning = shouldWarnPrice(s.orderSide, s.price, currentMarket);
        if (!warning) {
            confirmOrder(player, s);
            return;
        }
        s.screen = MarketScreen.PRICE_WARNING;
        context(player, s,
                "item", s.unit.getHoverName().getString(),
                "side", UiConfig.text(s.orderSide == OrderSide.BUY ? "my.purchase" : "my.sell"),
                "quantity", s.quantity,
                "price", CurrencyText.format(s.price),
                "total", safeTotal(s.price, s.quantity),
                "market_price", CurrencyText.format(referencePrice(currentMarket)));
        SimpleContainer box = blank(s);
        s.resetActions();
        LinkedHashMap<String, UiConfig.LineValue> v = new LinkedHashMap<>();
        v.put("warning.title", new UiConfig.LineValue(null, UiConfig.text("warning.title"), "warning"));
        v.put("warning.market", new UiConfig.LineValue(null,
                UiConfig.text("warning.market") + CurrencyText.format(referencePrice(currentMarket)), "warning"));
        v.put("warning.mine", new UiConfig.LineValue(null,
                UiConfig.text("warning.mine") + CurrencyText.format(s.price), "warning"));
        box.setItem(22, GuiItems.marketDisplay(s.unit, UiConfig.lines("warning", v, s.placeholders)));
        put(box, s, CONFIRM_BACK, uiButton(s, "warningChange", null, null), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, CONFIRM_PRIMARY, uiButton(s, "warningConfirm",
                MarketText.action(UiConfig.text("button.warningConfirm"), MarketPalette.byKey("warning")),
                List.of(MarketText.muted(UiConfig.text("button.warningConfirmLore")))),
                GuiAction.simple(GuiAction.Type.CONFIRM_ORDER));
        openBox(player, s, box);
    }

    private void confirmOrder(ServerPlayer player, MarketSession s) {
        if (s.pendingRequestId == null || s.quantity <= 0 || s.price <= 0) return;
        AuctionService.Outcome outcome;
        if (s.orderSide == OrderSide.BUY) {
            long needed;
            try {
                needed = Math.multiplyExact(s.price, (long) s.quantity);
            } catch (ArithmeticException e) {
                tell(player, UiConfig.text("chat.orderTooBig"), ChatFormatting.RED);
                return;
            }
            long balance = VAuctionCore.instance().economyGateway().getBalance(player.getUUID());
            if (balance < needed) {
                MarketText.bar(player, UiConfig.text("bar.noMoney"), MarketPalette.byKey("error"));
                tell(player, UiConfig.fmt("chat.fundsNeeded", "need", CurrencyText.format(needed),
                        "have", CurrencyText.format(balance)), ChatFormatting.RED);
                return;
            }
            outcome = service().createBuyOrder(player.getUUID(), s.unit, s.price, s.quantity,
                    s.pendingRequestId);
        } else {
            int available = service().availableCount(player.getUUID(), s.unit);
            if (available < s.quantity) {
                MarketText.bar(player, UiConfig.text("bar.noItems"), MarketPalette.byKey("error"));
                tell(player, UiConfig.fmt("chat.itemsNeeded", "need", s.quantity,
                        "have", available), ChatFormatting.RED);
                return;
            }
            outcome = service().createSellOrderFromInventory(player, s.unit, s.price,
                    s.quantity, s.pendingRequestId);
        }
        showOutcome(player, outcome);
        if (outcome.status() == AuctionService.Result.ACCEPTED_PENDING) {
            MarketSounds.placed(player);
            MarketText.bar(player, UiConfig.text("bar.orderPending"), MarketPalette.byKey("text"));
        } else if (outcome.isSuccess()) {
            MarketSounds.placed(player);
            if (outcome.order().remainingQuantity() == 0) {
                MarketText.bar(player, UiConfig.text("bar.orderFilled"), MarketPalette.byKey("success"));
            } else if (outcome.filledQuantity() > 0) {
                MarketText.bar(player, UiConfig.fmt(s.orderSide == OrderSide.BUY
                                ? "bar.orderPartialBuy" : "bar.orderPartialSell",
                        "q", outcome.filledQuantity(), "r", outcome.order().remainingQuantity()),
                        MarketPalette.byKey("success"));
            } else {
                MarketText.bar(player, UiConfig.text("bar.orderCreated"), MarketPalette.byKey("success"));
            }
        } else {
            MarketSounds.error(player);
        }
        s.screen = MarketScreen.MY;
        s.page = 0;
        renderMy(player, s);
    }

    private void renderMy(ServerPlayer player, MarketSession s) {
        s.screen = MarketScreen.MY;
        int[] contentSlots = UiConfig.slots("my", "content");
        Page<PlayerMarketActivity> page = read().playerActivity(player.getUUID(), s.page, contentSlots.length);
        s.page = page.page();
        context(player, s,
                "page", page.page() + 1,
                "pages", page.totalPages(),
                "results", page.items().size());
        SimpleContainer box = blank(s);
        s.resetActions();
        int i = 0;
        for (PlayerMarketActivity entry : page.items()) {
            ItemStack visual = read().visual(entry.item());
            if (visual.isEmpty()) visual = new ItemStack(Items.BARRIER);
            ItemStack icon;
            GuiAction action;
            if (entry.claimable()) {
                LinkedHashMap<String, UiConfig.LineValue> v = new LinkedHashMap<>();
                v.put("my.claimTitle", new UiConfig.LineValue(null, UiConfig.text("my.claimTitle"), "info"));
                v.put("my.rowQuantity", new UiConfig.LineValue("my.rowQuantity",
                        Integer.toString(entry.item().quantity()), "text"));
                v.put("my.type", new UiConfig.LineValue(null,
                        UiConfig.text(entry.deliveryType() == com.valorcraft.vauction.domain.delivery.DeliveryType.PURCHASED
                                ? "my.purchase" : "my.refund"), "muted"));
                v.put("my.claimHint", new UiConfig.LineValue(null, UiConfig.text("my.claimHint"), "success"));
                icon = GuiItems.marketDisplay(visual, UiConfig.lines("myClaim", v));
                action = GuiAction.delivery(entry.deliveryId());
            } else {
                boolean buy = entry.side() == OrderSide.BUY;
                boolean manual = entry.orderStatus() == OrderStatus.MANUAL_REVIEW;
                String status = manual ? UiConfig.text("my.manual")
                        : entry.filledQuantity() > 0 ? UiConfig.text("my.partial") + entry.filledQuantity()
                        + " из " + entry.originalQuantity()
                        : UiConfig.text(buy ? "my.waitSell" : "my.waitBuy");
                LinkedHashMap<String, UiConfig.LineValue> v = new LinkedHashMap<>();
                v.put("my.side", new UiConfig.LineValue(null, UiConfig.text(buy ? "my.purchase" : "my.sell"),
                        manual ? "warning" : buy ? "success" : "sell"));
                v.put("my.rowPrice", new UiConfig.LineValue("my.rowPrice",
                        CurrencyText.format(entry.pricePerUnit()), "text"));
                v.put("my.rowLeft", new UiConfig.LineValue("my.rowLeft",
                        entry.remainingQuantity() + " из " + entry.originalQuantity(), "text"));
                v.put("my.status", new UiConfig.LineValue(null, status,
                        manual ? "warning" : "muted"));
                v.put("my.manageHint", new UiConfig.LineValue(null,
                        UiConfig.text(entry.manageable() ? "my.manageHint" : "my.awaiting"), "muted"));
                icon = GuiItems.marketDisplay(visual, UiConfig.lines("myOrder", v));
                action = entry.manageable() ? GuiAction.manage(entry.orderId(), visual, buy,
                        entry.remainingQuantity(), entry.pricePerUnit()) : null;
            }
            put(box, s, contentSlots[i++], icon, action);
        }
        if (page.items().isEmpty()) {
            set(box, UiConfig.slot("my", "empty"), uiButton(s, "emptyMy", MarketText.text(UiConfig.text("my.emptyTitle")),
                    List.of(MarketText.muted(UiConfig.text("my.emptyLine1")),
                            MarketText.muted(UiConfig.text("my.emptyLine2")))));
        }
        myNavigation(box, s, page);
        openBox(player, s, box);
    }

    private void manageOrder(ServerPlayer player, MarketSession s, GuiAction action) {
        s.pendingCancelId = action.orderId();
        s.unit = action.item().copy();
        s.orderSide = action.number() >= 0 ? OrderSide.BUY : OrderSide.SELL;
        s.quantity = Math.abs(action.number());
        s.price = action.amount();
        s.screen = MarketScreen.ORDER_MANAGE;
        context(player, s,
                "item", s.unit.getHoverName().getString(),
                "side", UiConfig.text(s.orderSide == OrderSide.BUY ? "my.purchase" : "my.sell"),
                "quantity", s.quantity,
                "price", CurrencyText.format(s.price),
                "total", safeTotal(s.price, s.quantity));
        SimpleContainer box = blank(s);
        s.resetActions();
        LinkedHashMap<String, UiConfig.LineValue> v = new LinkedHashMap<>();
        v.put("manage.side", new UiConfig.LineValue(null,
                UiConfig.text(s.orderSide == OrderSide.BUY ? "my.purchase" : "my.sell"),
                s.orderSide == OrderSide.BUY ? "success" : "sell"));
        v.put("manage.price", new UiConfig.LineValue("manage.price", CurrencyText.format(s.price), "text"));
        v.put("manage.left", new UiConfig.LineValue("manage.left", Integer.toString(s.quantity), "text"));
        set(box, UiConfig.slot("manage", "item"),
                GuiItems.marketDisplay(s.unit, UiConfig.lines("manage", v, s.placeholders)));
        put(box, s, UiConfig.slot("manage", "back"), uiButton(s, "manageBack", null, null), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, UiConfig.slot("manage", "cancel"), uiButton(s, "manageCancel", null, null),
                GuiAction.simple(GuiAction.Type.PREPARE_CANCEL));
        openBox(player, s, box);
    }

    private void renderCancel(ServerPlayer player, MarketSession s) {
        context(player, s,
                "item", s.unit.getHoverName().getString(),
                "side", UiConfig.text(s.orderSide == OrderSide.BUY ? "my.purchase" : "my.sell"),
                "quantity", s.quantity,
                "price", CurrencyText.format(s.price),
                "total", safeTotal(s.price, s.quantity));
        SimpleContainer box = blank(s);
        s.resetActions();
        LinkedHashMap<String, UiConfig.LineValue> v = new LinkedHashMap<>();
        v.put("cancel.title", new UiConfig.LineValue(null, UiConfig.text("cancel.title"), "error"));
        v.put("cancel.body", new UiConfig.LineValue(null, UiConfig.text("cancel.body"), "text"));
        List<Component> cancelLines = UiConfig.lines("cancel", v, s.placeholders);
        Component cancelTitle = cancelLines.isEmpty() ? MarketText.text("") : cancelLines.get(0);
        List<Component> cancelLore = cancelLines.size() > 1 ? cancelLines.subList(1, cancelLines.size()) : List.of();
        box.setItem(22, GuiItems.namedButton(new ItemStack(Items.BARRIER), cancelTitle, cancelLore));
        put(box, s, CONFIRM_BACK, uiButton(s, "cancelNo", null, null), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, CONFIRM_PRIMARY, uiButton(s, "cancelYes",
                MarketText.action(UiConfig.text("button.cancelYes"), MarketPalette.byKey("error")),
                List.of(MarketText.muted(UiConfig.text("button.cancelYesLore")))),
                GuiAction.simple(GuiAction.Type.CONFIRM_CANCEL));
        openBox(player, s, box);
    }

    private void confirmCancel(ServerPlayer player, MarketSession s) {
        if (s.pendingCancelId == null) return;
        AuctionService.Outcome outcome = service().cancel(player.getUUID(), s.pendingCancelId, "market-gui");
        if (outcome.isSuccess() || outcome.status() == AuctionService.Result.ACCEPTED_PENDING) {
            MarketSounds.cancel(player);
            MarketText.bar(player, UiConfig.text("bar.cancelled"), MarketPalette.byKey("success"));
            tell(player, UiConfig.text("chat.cancelReturn"), ChatFormatting.GREEN);
        } else {
            MarketSounds.error(player);
            MarketText.bar(player, UiConfig.text("bar.failed"), MarketPalette.byKey("error"));
            showOutcome(player, outcome);
        }
        s.pendingCancelId = null;
        s.screen = MarketScreen.MY;
        renderMy(player, s);
    }

    private void claim(ServerPlayer player, MarketSession s, long deliveryId) {
        AuctionService.Outcome outcome = service().claimDelivery(player.getUUID(), deliveryId);
        if (outcome.isSuccess()) {
            MarketSounds.claim(player);
            MarketText.bar(player, UiConfig.text("bar.claim"), MarketPalette.byKey("success"));
        } else {
            MarketSounds.error(player);
            MarketText.bar(player, UiConfig.text("bar.failed"), MarketPalette.byKey("error"));
            showOutcome(player, outcome);
        }
        renderMy(player, s);
    }

    private void back(ServerPlayer player, MarketSession s) {
        if (s.screen == MarketScreen.PRICE_WARNING) {
            s.screen = MarketScreen.TRADE_LIMIT;
            renderEditor(player, s);
        } else if (s.screen == MarketScreen.CONFIRM_CANCEL) {
            manageOrder(player, s, GuiAction.manage(s.pendingCancelId, s.unit,
                    s.orderSide == OrderSide.BUY, s.quantity, s.price));
        } else if (s.screen == MarketScreen.ORDER_MANAGE) {
            renderMy(player, s);
        } else if (s.screen == MarketScreen.CATEGORIES) {
            renderMarkets(player, s);
        } else if (s.screen == MarketScreen.TRADE_IMMEDIATE || s.screen == MarketScreen.TRADE_LIMIT) {
            s.immediate = false;
            renderProduct(player, s);
        } else {
            s.immediate = false;
            returnToCatalogue(player, s);
        }
    }

    private void returnToCatalogue(ServerPlayer player, MarketSession s) {
        s.screen = s.searchActive ? MarketScreen.SEARCH : MarketScreen.BROWSE;
        s.orderSide = null;
        s.inventorySlot = -1;
        renderMarkets(player, s);
    }

    private void refreshCurrent(ServerPlayer player, MarketSession s) {
        switch (s.screen) {
            case BROWSE, SEARCH -> renderMarkets(player, s);
            case CATEGORIES -> renderCategories(player, s);
            case PRODUCT -> renderProduct(player, s);
            case MY -> renderMy(player, s);
            case TRADE_IMMEDIATE -> renderImmediateQuote(player, s);
            case TRADE_LIMIT -> renderEditor(player, s);
            default -> renderMarkets(player, s);
        }
    }

    private void openBox(ServerPlayer player, MarketSession s, SimpleContainer box) {
        String layout = layoutKey(s.screen);
        if (layout != null) UiConfig.decorate(layout, box, s.placeholders);
        int rows = box.getContainerSize() / 9;
        if (s.menu != null && s.contents != null && player.containerMenu == s.menu
                && s.openScreen == s.screen && s.openRows == rows) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) s.contents.setItem(slot, box.getItem(slot));
            fullSync(player, s.menu);
            return;
        }
        s.transitioning = true;
        try {
            player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
                s.containerId = id;
                s.contents = box;
                s.openScreen = s.screen;
                s.openRows = rows;
                s.menu = new ServerChestMenu(id, inventory, box, rows, this, s);
                return s.menu;
            }, MarketText.colored(screenTitle(s), MarketPalette.byKey("brand"))));
        } finally {
            s.transitioning = false;
        }
        if (s.menu != null) fullSync(player, s.menu);
        ServerChestMenu opened = s.menu;
        if (opened != null && player.getServer() != null) {
            player.getServer().execute(() -> {
                if (player.containerMenu == opened && s.menu == opened) fullSync(player, opened);
            });
        }
    }

    /**
     * First-frame guarantee: the client may receive the menu-open packet before its
     * chest-menu slots are initialized, leaving the bottom navigation empty until the
     * next per-slot broadcast. Pushing the full content packet right after the menu
     * exists makes every screen self-contained on the very first frame, and every
     * later state change uses the same full sync instead of per-slot deltas.
     */
    private static void fullSync(ServerPlayer player, ServerChestMenu menu) {
        player.connection.send(new ClientboundContainerSetContentPacket(
                menu.containerId, menu.incrementStateId(), menu.getItems(), menu.getCarried()));
    }

    private static SimpleContainer blank(MarketSession s) {
        return new SimpleContainer(screenRows(s.screen) * 9);
    }

    private static void context(ServerPlayer player, MarketSession s, Object... values) {
        s.placeholders.clear();
        s.placeholders.put("player", player.getGameProfile().getName());
        String screen = layoutKey(s.screen);
        s.placeholders.put("screen", screen == null ? s.screen.name().toLowerCase(java.util.Locale.ROOT) : screen);
        for (int i = 0; i + 1 < values.length; i += 2) {
            s.placeholders.put(String.valueOf(values[i]), String.valueOf(values[i + 1]));
        }
    }

    private static String safeTotal(long price, int quantity) {
        try {
            return CurrencyText.format(Math.multiplyExact(price, (long) quantity));
        } catch (ArithmeticException ignored) {
            return UiConfig.text("card.dash");
        }
    }

    private static int screenRows(MarketScreen screen) {
        String key = layoutKey(screen);
        return key == null ? 6 : UiConfig.rows(key);
    }

    private static String screenTitle(MarketSession s) {
        String key = layoutKey(s.screen);
        if (key != null) return UiConfig.title(key, s.placeholders);
        return UiConfig.text(s.screen == MarketScreen.PRICE_WARNING ? "window.priceWarning" : "window.cancel");
    }

    private static String layoutKey(MarketScreen screen) {
        return switch (screen) {
            case BROWSE -> "catalogue";
            case SEARCH -> "search";
            case CATEGORIES -> "categories";
            case PRODUCT -> "product";
            case TRADE_IMMEDIATE -> "immediate";
            case TRADE_LIMIT -> "limit";
            case MY -> "my";
            case ORDER_MANAGE -> "manage";
            case PRICE_WARNING, CONFIRM_CANCEL -> null;
        };
    }

    private static void catalogueNavigation(SimpleContainer box, MarketSession s, Page<?> page) {
        pageEdges(box, s, page, "catalogue");
        String category = UiConfig.text(s.filter == MarketFilter.ALL ? "filter.all" : s.filter.textKey);
        put(box, s, UiConfig.slot("catalogue", "categories"), uiButton(s, "categories",
                        MarketText.action(UiConfig.text("button.categories"), MarketPalette.byKey("brand")),
                        List.of(MarketText.muted(UiConfig.fmt("button.categoriesCurrent", "category", category)))),
                GuiAction.simple(GuiAction.Type.OPEN_FILTERS));
        put(box, s, UiConfig.slot("catalogue", "refresh"), uiButton(s, "refresh", null, null),
                GuiAction.simple(GuiAction.Type.REFRESH));
        put(box, s, UiConfig.slot("catalogue", "search"), uiButton(s, "search", null, null),
                GuiAction.simple(GuiAction.Type.SEARCH_HELP));
        catalogueInfo(box, s, page);
        put(box, s, UiConfig.slot("catalogue", "my"), uiButton(s, "my", null, null),
                GuiAction.simple(GuiAction.Type.MY));
        put(box, s, UiConfig.slot("catalogue", "help"), uiButton(s, "help", null, null),
                GuiAction.simple(GuiAction.Type.HELP));
    }

    private void renderCategories(ServerPlayer player, MarketSession s) {
        s.screen = MarketScreen.CATEGORIES;
        context(player, s, "category",
                UiConfig.text(s.filter == MarketFilter.ALL ? "filter.all" : s.filter.textKey));
        SimpleContainer box = blank(s);
        s.resetActions();
        set(box, UiConfig.slot("categories", "header"), uiButton(s, "categoriesHeader",
                MarketText.colored(UiConfig.text("filter.title"), MarketPalette.byKey("brand")),
                List.of(MarketText.muted(UiConfig.text("filter.titleLore")))));
        filterButton(box, s, UiConfig.slot("categories", "all"), MarketFilter.ALL);
        filterButton(box, s, UiConfig.slot("categories", "resources"), MarketFilter.RESOURCES);
        filterButton(box, s, UiConfig.slot("categories", "food"), MarketFilter.FOOD);
        filterButton(box, s, UiConfig.slot("categories", "tools"), MarketFilter.TOOLS);
        filterButton(box, s, UiConfig.slot("categories", "machines"), MarketFilter.MACHINES);
        filterButton(box, s, UiConfig.slot("categories", "other"), MarketFilter.OTHER);
        put(box, s, UiConfig.slot("categories", "back"), uiButton(s, "back", null, null), GuiAction.simple(GuiAction.Type.BACK));
        openBox(player, s, box);
    }

    private static void filterButton(SimpleContainer box, MarketSession s, int slot, MarketFilter filter) {
        boolean active = s.filter == filter;
        Component name = MarketText.colored((active ? "✓ " : "") + UiConfig.text(filter.textKey),
                MarketPalette.byKey(active ? "success" : "brand"));
        String buttonKey = switch (filter) {
            case ALL -> "filterAll";
            case RESOURCES -> "filterResources";
            case FOOD -> "filterFood";
            case TOOLS -> "filterTools";
            case MACHINES -> "filterMachines";
            case OTHER -> "filterOther";
        };
        put(box, s, slot, uiButton(s, buttonKey, name,
                        List.of(MarketText.muted(UiConfig.text(active ? "filter.active" : "filter.open")))),
                active ? null : GuiAction.number(GuiAction.Type.FILTER, filter.ordinal()));
    }

    private static void searchNavigation(SimpleContainer box, MarketSession s, Page<?> page) {
        pageEdges(box, s, page, "search");
        put(box, s, UiConfig.slot("search", "newSearch"), uiButton(s, "newSearch", null, null),
                GuiAction.simple(GuiAction.Type.SEARCH_HELP));
        catalogueInfo(box, s, page);
        put(box, s, UiConfig.slot("search", "catalogue"), uiButton(s, "catalogue", null, null),
                GuiAction.simple(GuiAction.Type.BROWSE));
    }

    private static void myNavigation(SimpleContainer box, MarketSession s, Page<?> page) {
        pageEdges(box, s, page, "my");
        myInfo(box, s, page);
        put(box, s, UiConfig.slot("my", "catalogue"), uiButton(s, "allGoods", null, null),
                GuiAction.simple(GuiAction.Type.HOME));
    }

    private static void pageEdges(SimpleContainer box, MarketSession s, Page<?> page, String layout) {
        if (page.hasPrevious()) put(box, s, UiConfig.slot(layout, "previous"),
                uiButton(s, "prev", null, null),
                GuiAction.number(GuiAction.Type.PAGE, -1));
        if (page.hasNext()) put(box, s, UiConfig.slot(layout, "next"),
                uiButton(s, "next", null, null),
                GuiAction.number(GuiAction.Type.PAGE, 1));
    }

    /** Slot 49 — central information item: controls hint plus page counter. Never clickable. */
    private static void catalogueInfo(SimpleContainer box, MarketSession s, Page<?> page) {
        boolean multi = page.totalPages() > 1;
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        if (multi) {
            lore.add(MarketText.labelValue(UiConfig.text("nav.page"),
                    (page.page() + 1) + " / " + page.totalPages(), MarketPalette.byKey("text")));
            lore.add(MarketText.muted(UiConfig.text("nav.openHint")));
        } else {
            lore.add(MarketText.text(UiConfig.text("nav.infoTitle")));
            lore.add(MarketText.muted(UiConfig.text("nav.openHint")));
        }
        String title = s.filter == MarketFilter.ALL ? UiConfig.text("nav.infoTitle") : UiConfig.text(s.filter.textKey);
        put(box, s, UiConfig.slot(s.screen == MarketScreen.SEARCH ? "search" : "catalogue", "info"),
                buttonOn(new ItemStack(UiConfig.button("infoBook").iconItem()), UiConfig.button("infoBook"),
                MarketText.colored(title, MarketPalette.byKey("brand")), lore, s.placeholders),
                null);
    }

    /** Slot 49 on the «Моё» screen — page counter or a short description. Never clickable. */
    private static void myInfo(SimpleContainer box, MarketSession s, Page<?> page) {
        boolean multi = page.totalPages() > 1;
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        if (multi) {
            lore.add(MarketText.labelValue(UiConfig.text("nav.page"),
                    (page.page() + 1) + " / " + page.totalPages(), MarketPalette.byKey("text")));
        } else {
            lore.add(MarketText.text(UiConfig.text("nav.myTitle")));
        }
        lore.add(MarketText.muted(UiConfig.text("nav.ordersHint")));
        put(box, s, UiConfig.slot("my", "info"),
                buttonOn(new ItemStack(UiConfig.button("infoBook").iconItem()), UiConfig.button("infoBook"),
                MarketText.colored(UiConfig.text("nav.myTitle"), MarketPalette.byKey("brand")), lore,
                        s.placeholders), null);
    }

    private static void put(SimpleContainer box, MarketSession s, int slot, ItemStack item, GuiAction action) {
        if (slot < 0) return;
        box.setItem(slot, item.copy());
        if (action != null) s.actions.put(slot, action);
    }

    private static void set(SimpleContainer box, int slot, ItemStack item) {
        if (slot >= 0) box.setItem(slot, item);
    }

    private static ItemStack button(net.minecraft.world.item.Item item, String name, String lore) {
        return GuiItems.namedButton(new ItemStack(item), MarketText.colored(name, MarketPalette.byKey("brand")),
                lore == null || lore.isBlank() ? List.of() : List.of(MarketText.muted(lore)));
    }

    private static ItemStack button(net.minecraft.world.item.Item item, Component name, List<Component> lore) {
        return GuiItems.namedButton(new ItemStack(item), name, lore);
    }

    /** Кнопка из UiConfig: иконка и подписи берутся из конфига, переопределяются при необходимости. */
    private static ItemStack uiButton(MarketSession s, String key, Component name, List<Component> lore) {
        UiConfig.ButtonCfg cfg = UiConfig.button(key);
        return buttonOn(new ItemStack(cfg.iconItem()), cfg, name, lore, s.placeholders);
    }

    private static void quantityControls(SimpleContainer box, MarketSession s, OrderSide side, String layout) {
        if (side == OrderSide.BUY) {
            quantityPreset(box, s, UiConfig.slot(layout, "quantityOne"), 1);
            quantityPreset(box, s, UiConfig.slot(layout, "quantityBulk"), 64);
        } else {
            quantityPreset(box, s, UiConfig.slot(layout, "quantityOne"), 1);
            put(box, s, UiConfig.slot(layout, "quantityBulk"), uiButton(s, "quantityAll",
                    MarketText.action(UiConfig.text("quantity.all"), MarketPalette.byKey("sell")),
                    List.of(MarketText.muted(UiConfig.text("button.quantityAllLore")))),
                    GuiAction.simple(GuiAction.Type.SET_MAX_QUANTITY));
        }
        put(box, s, UiConfig.slot(layout, "quantityOther"), uiButton(s, "quantityOther",
                MarketText.action(UiConfig.text("quantity.other"), MarketPalette.byKey("text")),
                List.of(MarketText.muted(UiConfig.text("button.quantityOtherLore")))),
                GuiAction.simple(GuiAction.Type.EXACT_QUANTITY));
    }

    private static void quantityPreset(SimpleContainer box, MarketSession s, int slot, int quantity) {
        ItemStack icon = uiButton(s, "quantityPreset",
                MarketText.colored(quantity == 1 ? UiConfig.text("quantity.one") : Integer.toString(quantity),
                        MarketPalette.byKey("brand")),
                List.of(MarketText.muted(UiConfig.text("button.quantityPresetLore"))));
        icon.setCount(Math.min(quantity, 64));
        put(box, s, slot, icon, GuiAction.quantityPreset(quantity));
    }

    static boolean shouldWarnPrice(OrderSide side, long entered, MarketSummary market) {
        long reference = referencePrice(market);
        if (entered <= 0 || reference <= 0) return false;
        java.math.BigInteger value = java.math.BigInteger.valueOf(entered);
        java.math.BigInteger ref = java.math.BigInteger.valueOf(reference);
        return side == OrderSide.BUY
                ? value.compareTo(ref.multiply(java.math.BigInteger.valueOf(5))) >= 0
                : value.multiply(java.math.BigInteger.valueOf(5)).compareTo(ref) <= 0;
    }

    static long referencePrice(MarketSummary market) {
        if (market == null) return 0;
        long[] values = java.util.stream.LongStream.of(market.bestBid(), market.bestAsk(),
                market.lastTradePrice()).filter(v -> v > 0).sorted().toArray();
        if (values.length < 2) return 0;
        if (values.length == 3) return values[1];
        return values[0] / 2 + values[1] / 2 + (values[0] % 2 + values[1] % 2) / 2;
    }

    private static String moneyOrDash(long amount) {
        return amount <= 0 ? UiConfig.text("card.dash") : CurrencyText.format(amount);
    }

    private static String moneyOrUnavailable(long amount) {
        return amount <= 0 ? UiConfig.text("card.unavailable") : CurrencyText.format(amount);
    }

    private static List<Component> cardLore(MarketSummary m) {
        LinkedHashMap<String, UiConfig.LineValue> v = new LinkedHashMap<>();
        v.put("catalog.buy", new UiConfig.LineValue("catalog.buy", moneyOrUnavailable(m.bestAsk()), "success"));
        v.put("catalog.sell", new UiConfig.LineValue("catalog.sell", moneyOrUnavailable(m.bestBid()), "sell"));
        v.put("catalog.open", new UiConfig.LineValue(null, UiConfig.text("catalog.open"), "info"));
        return UiConfig.lines("catalogCard", v);
    }

    private static ItemStack buttonOn(ItemStack stack, UiConfig.ButtonCfg cfg, Component name,
                                      List<Component> lore, Map<String, String> placeholders) {
        Component configuredName = cfg.name() != null
                ? MarketText.colored(UiConfig.format(cfg.name(), placeholders), MarketPalette.byKey(cfg.colorKey()))
                : cfg.nameKey() == null ? Component.empty()
                : MarketText.colored(UiConfig.format(UiConfig.text(cfg.nameKey()), placeholders),
                        MarketPalette.byKey(cfg.colorKey()));
        List<Component> configuredLore;
        if (!cfg.lore().isEmpty()) {
            configuredLore = cfg.lore().stream()
                    .map(line -> MarketText.colored(UiConfig.format(line, placeholders),
                            MarketPalette.byKey(cfg.loreColorKey())))
                    .toList();
        } else if (cfg.loreKey() != null) {
            configuredLore = List.of(MarketText.colored(
                    UiConfig.format(UiConfig.text(cfg.loreKey()), placeholders),
                    MarketPalette.byKey(cfg.loreColorKey())));
        } else {
            configuredLore = List.of();
        }
        return GuiItems.namedButton(stack,
                name == null ? configuredName
                        : name.copy().withStyle(style -> style
                        .withColor(MarketPalette.byKey(cfg.colorKey())).withItalic(false)),
                lore == null ? configuredLore : lore);
    }

    /** Итоговая сумма сделки: покупатель платит gross, продавец получает net после комиссии. */
    private static long tradeAmount(List<Trade> trades, boolean buyerPays) {
        long total = 0;
        try {
            for (Trade t : trades) {
                total = Math.addExact(total, buyerPays ? t.grossMinor() : t.sellerNet());
            }
        } catch (ArithmeticException e) {
            return 0;
        }
        return total;
    }

    private static void showOutcome(ServerPlayer player, AuctionService.Outcome outcome) {
        if (outcome.status() == AuctionService.Result.ACCEPTED_PENDING) {
            tell(player, UiConfig.text("chat.accepted"), ChatFormatting.YELLOW);
        } else if (outcome.isSuccess()) {
            if (outcome.order().remainingQuantity() == 0) {
                tell(player, UiConfig.text("chat.orderLeftEmpty"), ChatFormatting.GREEN);
            } else if (outcome.filledQuantity() > 0) {
                tell(player, UiConfig.fmt("chat.orderPartial", "q", outcome.filledQuantity(),
                        "r", outcome.order().remainingQuantity()), ChatFormatting.GREEN);
            } else {
                tell(player, UiConfig.text("chat.orderWaiting"), ChatFormatting.GREEN);
            }
        } else {
            String friendly = switch (outcome.status()) {
                case INSUFFICIENT_FUNDS -> UiConfig.text("chat.funds");
                case INSUFFICIENT_ITEMS -> UiConfig.text("chat.items");
                case INVENTORY_FULL -> UiConfig.text("chat.inventoryFull");
                case NOT_YOUR_ORDER -> UiConfig.text("chat.notYours");
                case ORDER_NOT_FOUND -> UiConfig.text("chat.orderChanged");
                case INVALID_PRICE, INVALID_QUANTITY -> UiConfig.text("chat.badPrice");
                case OVER_LIMIT -> UiConfig.text("chat.overLimit");
                case BLACKLISTED -> UiConfig.text("chat.blacklisted");
                case MARKET_DISABLED -> UiConfig.text("chat.disabled");
                default -> UiConfig.text("chat.genericFail");
            };
            tell(player, "✕ " + friendly, ChatFormatting.RED);
        }
    }

    private static void onboarding(ServerPlayer player) {
        player.sendSystemMessage(MarketText.brand());
        player.sendSystemMessage(MarketText.text(UiConfig.text("onboarding.1")));
        player.sendSystemMessage(MarketText.text(UiConfig.text("onboarding.2")));
        player.sendSystemMessage(Component.literal(UiConfig.text("onboarding.help"))
                .withStyle(style -> style.withColor(MarketPalette.byKey("info"))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah help"))));
    }

    private static void tutorial(ServerPlayer player) {
        player.sendSystemMessage(MarketText.brand());
        player.sendSystemMessage(MarketText.muted(UiConfig.text("tutorial.1")));
        player.sendSystemMessage(MarketText.muted(UiConfig.text("tutorial.2")));
        player.sendSystemMessage(Component.literal(UiConfig.text("tutorial.commands"))
                .withStyle(style -> style.withColor(MarketPalette.byKey("info"))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah help"))));
    }

    private static void searchHelp(ServerPlayer player) {
        player.sendSystemMessage(MarketText.brand());
        player.sendSystemMessage(MarketText.muted(UiConfig.text("searchHelp.title")));
        player.sendSystemMessage(Component.literal(UiConfig.text("searchHelp.command"))
                .withStyle(style -> style.withColor(MarketPalette.byKey("info"))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/ah search "))));
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    private static boolean ready(ServerPlayer player) {
        if (!VAuctionCore.instance().isRunning()) {
            tell(player, UiConfig.text("chat.notReady"), ChatFormatting.RED);
            return false;
        }
        return true;
    }

    private static AuctionReadService read() { return VAuctionCore.instance().auctionReadService(); }
    private static AuctionService service() { return VAuctionCore.instance().auctionService(); }
}

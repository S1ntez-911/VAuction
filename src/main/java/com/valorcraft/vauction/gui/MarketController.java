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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned navigation and action mapping for vanilla inventory screens. */
public final class MarketController {
    private static final Logger LOGGER = LogManager.getLogger("VAuction");
    static final int NAV_PREVIOUS = 45;
    static final int NAV_SEARCH = 47;
    static final int NAV_INFO = 49;
    static final int NAV_MY = 51;
    static final int NAV_NEXT = 53;
    static final int TRADE_BACK = 45;
    static final int TRADE_SECONDARY = 47;
    static final int TRADE_PRIMARY = 49;
    static final int PRICE_INFO = 31;
    private static final int[] CARD_SLOTS = java.util.stream.IntStream.range(0, 45).toArray();
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
        if (slotId < 0 || slotId >= 54) return;
        GuiAction action = session.actions.get(slotId);
        if (action == null) return;
        session.executing = true;
        try {
            if (action.type() == GuiAction.Type.OPEN_TRADE) {
                MarketSounds.navigation(player);
                openTrade(player, session, action.item(), button == 1 ? OrderSide.SELL : OrderSide.BUY, -1);
            } else {
                handle(player, session, action);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Market GUI action failed: player={}, screen={}, action={}",
                    player.getUUID(), session.screen, action.type(), e);
            tell(player, "Биржа временно недоступна. Попробуйте ещё раз.", ChatFormatting.RED);
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

    private void handle(ServerPlayer player, MarketSession s, GuiAction a) {
        if (a.type() == GuiAction.Type.HOME || a.type() == GuiAction.Type.BROWSE
                || a.type() == GuiAction.Type.MY || a.type() == GuiAction.Type.BACK) {
            MarketSounds.navigation(player);
        }
        switch (a.type()) {
            case HOME -> { s.screen = MarketScreen.BROWSE; s.cataloguePage = 0; s.search = ""; s.searchActive = false;
                s.orderSide = null; s.inventorySlot = -1; renderMarkets(player, s); }
            case BROWSE -> { s.screen = MarketScreen.BROWSE; s.cataloguePage = 0; s.search = ""; s.searchActive = false; renderMarkets(player, s); }
            case HELP -> tutorial(player);
            case SEARCH_HELP -> searchHelp(player);
            case MY -> { s.screen = MarketScreen.MY; s.page = 0; renderMy(player, s); }
            case PAGE -> { if (s.screen == MarketScreen.BROWSE || s.screen == MarketScreen.SEARCH) {
                    s.cataloguePage = Math.max(0, s.cataloguePage + a.number());
                } else s.page = Math.max(0, s.page + a.number());
                MarketSounds.page(player); refreshCurrent(player, s); }
            case BUY -> { MarketSounds.mode(player); beginOrder(player, s, OrderSide.BUY); }
            case SELL -> { MarketSounds.mode(player); beginOrder(player, s, OrderSide.SELL); }
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
                tell(player, "✕ Недостаточно предметов", ChatFormatting.RED);
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
            tell(player, "✕ Недостаточно предметов", ChatFormatting.RED);
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
        player.sendSystemMessage(MarketText.text(target == TradeDraft.InputTarget.PRICE
                ? "Введите цену за штуку:" : "Укажите своё количество:"));
        player.sendSystemMessage(Component.literal("/ah set <число>")
                .withStyle(style -> style.withColor(MarketPalette.INFO)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/ah set "))));
        player.sendSystemMessage(MarketText.muted(target == TradeDraft.InputTarget.PRICE
                ? "Заявка снова откроется с этой ценой."
                : "Сделка снова откроется с этим количеством."));
        MarketSounds.preset(player, false);
    }

    /** Re-opens the trade from a draft after /ah set ran while the GUI was closed. */
    private void reopenFromDraft(ServerPlayer player, TradeDraft draft) {
        String key = read().marketKey(draft.unit);
        if (key == null) {
            drafts.remove(player.getUUID());
            tell(player, "Этот предмет больше нельзя открыть на бирже.", ChatFormatting.RED);
            return;
        }
        if (draft.side == OrderSide.SELL
                && service().availableCount(player.getUUID(), draft.unit) <= 0) {
            drafts.remove(player.getUUID());
            tell(player, "В инвентаре больше нет точно такого предмета.", ChatFormatting.RED);
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
        Page<MarketCard> page = read().markets(s.cataloguePage, s.searchActive ? s.search : "");
        s.cataloguePage = page.page();
        SimpleContainer box = blank();
        s.resetActions();
        int i = 0;
        for (MarketCard card : page.items()) {
            ItemStack visual = read().visual(card.visual());
            if (visual.isEmpty()) visual = new ItemStack(Items.BARRIER);
            MarketSummary m = card.summary();
            ItemStack icon = GuiItems.decorateMarketItem(visual, List.of(
                    MarketText.labelValue("Можно купить", moneyOrUnavailable(m.bestAsk()), MarketPalette.SUCCESS),
                    MarketText.labelValue("Можно продать", moneyOrUnavailable(m.bestBid()), MarketPalette.SELL),
                    MarketText.labelValue("Последняя сделка", moneyOrDash(m.lastTradePrice()), MarketPalette.TEXT),
                    Component.empty(), MarketText.colored("ЛКМ → купить", MarketPalette.SUCCESS),
                    MarketText.colored("ПКМ → продать", MarketPalette.SELL)));
            put(box, s, CARD_SLOTS[i++], icon, GuiAction.trade(visual));
        }
        if (page.items().isEmpty() && s.screen == MarketScreen.SEARCH) {
            tell(player, "По запросу «" + s.search + "» ничего не найдено.", ChatFormatting.YELLOW);
            box.setItem(22, GuiItems.namedButton(new ItemStack(Items.COMPASS),
                    MarketText.action("◆ Ничего не найдено", MarketPalette.WARNING),
                    List.of(MarketText.muted("Товар пока не торгуется."),
                            MarketText.muted("Первую заявку можно создать через /ah buy или /ah sell."))));
        } else if (page.items().isEmpty()) {
            box.setItem(22, GuiItems.namedButton(new ItemStack(Items.PAPER),
                    MarketText.text("На бирже пока нет товаров."), List.of(
                            MarketText.muted("Используйте /ah sell или /ah buy,"),
                            MarketText.muted("чтобы создать первую заявку."))));
        }
        if (s.screen == MarketScreen.SEARCH) searchNavigation(box, s, page);
        else catalogueNavigation(box, s, page);
        openBox(player, s, box, "Биржа ValorCraft");
    }

    private void openTrade(ServerPlayer player, MarketSession s, ItemStack unit,
                           OrderSide side, int inventorySlot) {
        unit = unit.copy();
        unit.setCount(1);
        String key = read().marketKey(unit);
        if (key == null) {
            tell(player, "Этот предмет нельзя открыть на бирже.", ChatFormatting.RED);
            return;
        }
        s.unit = unit;
        s.marketKey = key;
        s.inventorySlot = inventorySlot;
        beginImmediate(player, s, side);
    }

    private void beginImmediate(ServerPlayer player, MarketSession s, OrderSide side) {
        boolean switchingMode = s.screen == MarketScreen.TRADE_LIMIT || s.screen == MarketScreen.PRICE_WARNING;
        int available = side == OrderSide.SELL ? service().availableCount(player.getUUID(), s.unit) : 0;
        if (side == OrderSide.SELL && available <= 0) {
            tell(player, "В инвентаре нет точно такого предмета. Возьмите его в руку или выберите другой рынок.",
                    ChatFormatting.RED);
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
        SimpleContainer box = blank();
        s.resetActions();
        boolean buy = s.orderSide == OrderSide.BUY;
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        AuctionReadService.MarketView market = read().market(s.unit);
        MarketSummary summary = market == null ? null : market.card().summary();
        lore.add(MarketText.action(buy ? "ПОКУПКА" : "ПРОДАЖА",
                buy ? MarketPalette.SUCCESS : MarketPalette.SELL));
        lore.add(MarketText.labelValue(buy ? "Сейчас от" : "Сейчас покупают",
                moneyOrUnavailable(summary == null ? 0 : (buy
                        ? summary.bestAsk() : summary.bestBid())), MarketPalette.TEXT));
        if (!buy) {
            lore.add(MarketText.labelValue("У вас", Integer.toString(available), MarketPalette.TEXT));
        }
        lore.add(Component.empty());
        lore.add(MarketText.labelValue("Количество", Integer.toString(quote.requestedQuantity()), MarketPalette.TEXT));
        if (quote.fillableQuantity() < quote.requestedQuantity()) {
            lore.add(MarketText.labelValue("Доступно", quote.fillableQuantity() + " из "
                    + quote.requestedQuantity(), MarketPalette.WARNING));
        }
        if (quote.executable()) {
            lore.add(MarketText.labelValue(buy ? "Итого" : "Получите",
                    CurrencyText.format(quote.expectedTotal()), MarketPalette.TEXT));
            if (quote.insufficientLiquidity()) {
                lore.add(MarketText.muted("Остаток не станет ожидающей заявкой."));
            }
        } else {
            lore.add(MarketText.colored("Сейчас нет подходящих предложений.", MarketPalette.WARNING));
        }
        box.setItem(13, GuiItems.decorateMarketItem(s.unit, lore));
        quantityControls(box, s, s.orderSide);
        put(box, s, TRADE_BACK, button(MarketIcons.BACK, "Назад", "К каталогу"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, TRADE_SECONDARY, button(MarketIcons.MODE_SWITCH, "Своя цена", "Заявка будет ждать подходящего предложения"),
                GuiAction.simple(buy ? GuiAction.Type.BUY : GuiAction.Type.SELL));
        if (quote.executable()) {
            put(box, s, TRADE_PRIMARY, button(buy ? MarketIcons.PRIMARY_BUY : MarketIcons.PRIMARY_SELL,
                    MarketText.action(buy ? "✓ Купить сейчас" : "✓ Продать сейчас",
                            buy ? MarketPalette.SUCCESS : MarketPalette.SELL),
                    List.of(MarketText.muted(buy
                            ? "Не дороже показанной цены" : "Не дешевле показанной цены"))),
                    GuiAction.simple(GuiAction.Type.CONFIRM_IMMEDIATE));
        } else {
            put(box, s, TRADE_PRIMARY, button(MarketIcons.DISABLED, "Нет предложений", ""), null);
        }
        openBox(player, s, box, "Биржа ValorCraft");
    }

    private void confirmImmediate(ServerPlayer player, MarketSession s) {
        AuctionReadService.ImmediateQuote quote = s.quote;
        if (quote == null || !quote.executable() || s.pendingRequestId == null) return;
        if (s.orderSide == OrderSide.BUY) {
            long reserve;
            try {
                reserve = Math.multiplyExact(quote.worstExecutionPrice(), (long) quote.fillableQuantity());
            } catch (ArithmeticException e) {
                tell(player, "Сумма слишком велика. Уменьшите количество.", ChatFormatting.RED);
                return;
            }
            long balance = VAuctionCore.instance().economyGateway().getBalance(player.getUUID());
            if (balance < reserve) {
                tell(player, "Недостаточно средств. Нужно до: " + CurrencyText.format(reserve)
                        + ", доступно: " + CurrencyText.format(balance) + ". Уменьшите количество.",
                        ChatFormatting.RED);
                return;
            }
        }
        AuctionService.Outcome outcome = s.orderSide == OrderSide.BUY
                ? service().executeBuyNow(player.getUUID(), s.unit, quote.worstExecutionPrice(),
                        quote.fillableQuantity(), s.pendingRequestId)
                : service().executeSellNow(player.getUUID(), s.unit, quote.worstExecutionPrice(),
                        quote.fillableQuantity(), s.pendingRequestId);
        long filled = outcome.filledQuantity();
        if (outcome.isSuccess()) MarketSounds.success(player); else MarketSounds.error(player);
        if (!outcome.isSuccess()) {
            showOutcome(player, outcome);
        } else if (filled == 0) {
            tell(player, s.orderSide == OrderSide.BUY
                            ? "Рынок изменился: по подтверждённой цене ничего не куплено. Средства освобождены."
                            : "Рынок изменился: по подтверждённой цене ничего не продано. Предметы доступны в «Моём».",
                    ChatFormatting.YELLOW);
        } else if (s.orderSide == OrderSide.BUY) {
            tell(player, "Куплено: " + filled + " " + s.unit.getHoverName().getString()
                    + ". Предметы готовы в «Моём».", ChatFormatting.GREEN);
        } else {
            tell(player, "Продано: " + filled + " " + s.unit.getHoverName().getString()
                    + ". Неисполненный остаток доступен в «Моём».", ChatFormatting.GREEN);
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
            tell(player, "В инвентаре нет точно такого предмета.", ChatFormatting.RED);
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
        SimpleContainer box = blank();
        s.resetActions();
        long total;
        try { total = Math.multiplyExact(s.price, (long) s.quantity); }
        catch (ArithmeticException ignored) { total = Long.MAX_VALUE; }
        box.setItem(13, GuiItems.decorateMarketItem(s.unit, List.of(
                MarketText.action(s.orderSide == OrderSide.BUY ? "ЗАЯВКА НА ПОКУПКУ" : "ЗАЯВКА НА ПРОДАЖУ",
                        s.orderSide == OrderSide.BUY ? MarketPalette.SUCCESS : MarketPalette.SELL),
                MarketText.labelValue("Количество", Integer.toString(s.quantity), MarketPalette.TEXT),
                MarketText.labelValue("Цена за штуку", CurrencyText.format(s.price), MarketPalette.TEXT),
                MarketText.labelValue(s.orderSide == OrderSide.BUY ? "Резерв" : "Сумма заявки",
                        CurrencyText.format(total), MarketPalette.TEXT),
                s.orderSide == OrderSide.SELL
                        ? MarketText.labelValue("Доступно", Integer.toString(service().availableCount(player.getUUID(), s.unit)), MarketPalette.TEXT)
                        : MarketText.muted("Средства резервируются после подтверждения"))));
        quantityControls(box, s, s.orderSide);
        put(box, s, PRICE_INFO, button(MarketIcons.PRICE_INFO, "Изменить цену", "Указать свою цену за штуку"),
                GuiAction.simple(GuiAction.Type.EXACT_PRICE));
        put(box, s, TRADE_BACK, button(MarketIcons.BACK, "Назад", "К каталогу"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, TRADE_SECONDARY, button(MarketIcons.MODE_SWITCH,
                buy ? "Купить сейчас" : "Продать сейчас", "Вернуться к рыночной цене"),
                GuiAction.simple(buy ? GuiAction.Type.BUY_NOW : GuiAction.Type.SELL_NOW));
        put(box, s, TRADE_PRIMARY, button(MarketIcons.SUBMIT_LIMIT,
                MarketText.action("✓ Выставить заявку",
                        buy ? MarketPalette.SUCCESS : MarketPalette.SELL),
                List.of(MarketText.muted("Создать заявку по указанной цене"))), GuiAction.simple(GuiAction.Type.REVIEW));
        openBox(player, s, box, "Биржа ValorCraft");
    }

    private void reviewOrSubmit(ServerPlayer player, MarketSession s) {
        try {
            Math.multiplyExact(s.price, (long) s.quantity);
        } catch (ArithmeticException e) {
            tell(player, "Сумма слишком велика.", ChatFormatting.RED);
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
        SimpleContainer box = blank();
        s.resetActions();
        String[] lines = new String[]{"⚠ Проверьте цену",
                "Рынок: ~" + CurrencyText.format(referencePrice(currentMarket)),
                "Ваша цена: " + CurrencyText.format(s.price)};
        box.setItem(22, GuiItems.decorateMarketItem(s.unit, java.util.Arrays.stream(lines)
                .<Component>map(line -> MarketText.colored(line, MarketPalette.WARNING))
                .toList()));
        put(box, s, TRADE_BACK, button(MarketIcons.BACK, "Изменить", "Вернуться к параметрам"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, TRADE_PRIMARY, button(MarketIcons.WARN_CONFIRM,
                MarketText.action("✓ Всё равно", MarketPalette.WARNING),
                List.of(MarketText.muted("Цена останется без изменений"))),
                GuiAction.simple(GuiAction.Type.CONFIRM_ORDER));
        openBox(player, s, box, "Биржа ValorCraft");
    }

    private void confirmOrder(ServerPlayer player, MarketSession s) {
        if (s.pendingRequestId == null || s.quantity <= 0 || s.price <= 0) return;
        AuctionService.Outcome outcome;
        if (s.orderSide == OrderSide.BUY) {
            long needed;
            try {
                needed = Math.multiplyExact(s.price, (long) s.quantity);
            } catch (ArithmeticException e) {
                tell(player, "Сумма слишком велика. Уменьшите цену или количество.", ChatFormatting.RED);
                return;
            }
            long balance = VAuctionCore.instance().economyGateway().getBalance(player.getUUID());
            if (balance < needed) {
                tell(player, "Недостаточно средств. Нужно: " + CurrencyText.format(needed)
                        + ", доступно: " + CurrencyText.format(balance) + ". Уменьшите цену или количество.",
                        ChatFormatting.RED);
                return;
            }
            outcome = service().createBuyOrder(player.getUUID(), s.unit, s.price, s.quantity,
                    s.pendingRequestId);
        } else {
            int available = service().availableCount(player.getUUID(), s.unit);
            if (available < s.quantity) {
                tell(player, "Недостаточно " + s.unit.getHoverName().getString() + ". Нужно: "
                        + s.quantity + ", у вас: " + available + ". Уменьшите количество.", ChatFormatting.RED);
                return;
            }
            outcome = service().createSellOrderFromInventory(player, s.unit, s.price,
                    s.quantity, s.pendingRequestId);
        }
        showOutcome(player, outcome);
        if (outcome.isSuccess()) MarketSounds.success(player); else MarketSounds.error(player);
        s.screen = MarketScreen.MY;
        s.page = 0;
        renderMy(player, s);
    }

    private void renderMy(ServerPlayer player, MarketSession s) {
        s.screen = MarketScreen.MY;
        Page<PlayerMarketActivity> page = read().playerActivity(player.getUUID(), s.page);
        s.page = page.page();
        SimpleContainer box = blank();
        s.resetActions();
        int i = 0;
        for (PlayerMarketActivity entry : page.items()) {
            ItemStack visual = read().visual(entry.item());
            if (visual.isEmpty()) visual = new ItemStack(Items.BARRIER);
            ItemStack icon;
            GuiAction action;
            if (entry.claimable()) {
                icon = GuiItems.decorateMarketItem(visual, List.of(
                        MarketText.action("Готово к получению", MarketPalette.INFO),
                        MarketText.labelValue("Количество", Integer.toString(entry.item().quantity()), MarketPalette.TEXT),
                        MarketText.muted(entry.deliveryType() == com.valorcraft.vauction.domain.delivery.DeliveryType.PURCHASED
                                ? "Покупка на бирже" : "Возврат после заявки"),
                        MarketText.colored("ЛКМ → забрать", MarketPalette.SUCCESS)));
                action = GuiAction.delivery(entry.deliveryId());
            } else {
                boolean buy = entry.side() == OrderSide.BUY;
                boolean manual = entry.orderStatus() == OrderStatus.MANUAL_REVIEW;
                String status = manual ? "⚠ Нужна проверка администратора"
                        : entry.filledQuantity() > 0 ? "Частично исполнено: " + entry.filledQuantity() + " из " + entry.originalQuantity()
                        : buy ? "Ждёт продавца" : "Ждёт покупателя";
                icon = GuiItems.decorateMarketItem(visual, List.of(
                        MarketText.action(buy ? "Покупка" : "Продажа",
                                manual ? MarketPalette.WARNING : buy ? MarketPalette.SUCCESS : MarketPalette.SELL),
                        MarketText.labelValue("Цена", CurrencyText.format(entry.pricePerUnit()), MarketPalette.TEXT),
                        MarketText.labelValue("Осталось", entry.remainingQuantity() + " из " + entry.originalQuantity(), MarketPalette.TEXT),
                        MarketText.colored(status, manual ? MarketPalette.WARNING : MarketPalette.MUTED),
                        entry.manageable() ? MarketText.muted("ЛКМ → управление") : MarketText.muted("Ожидает проверки")));
                action = entry.manageable() ? GuiAction.manage(entry.orderId(), visual, buy,
                        entry.remainingQuantity(), entry.pricePerUnit()) : null;
            }
            put(box, s, CARD_SLOTS[i++], icon, action);
        }
        if (page.items().isEmpty()) {
            box.setItem(22, GuiItems.namedButton(new ItemStack(Items.ENDER_CHEST), MarketText.text("Здесь пока пусто."),
                    List.of(MarketText.muted("Активные заявки и покупки"),
                            MarketText.muted("появятся здесь."))));
        }
        myNavigation(box, s, page);
        openBox(player, s, box, "Биржа ValorCraft");
    }

    private void manageOrder(ServerPlayer player, MarketSession s, GuiAction action) {
        s.pendingCancelId = action.orderId();
        s.unit = action.item().copy();
        s.orderSide = action.number() >= 0 ? OrderSide.BUY : OrderSide.SELL;
        s.quantity = Math.abs(action.number());
        s.price = action.amount();
        s.screen = MarketScreen.ORDER_MANAGE;
        SimpleContainer box = blank();
        s.resetActions();
        box.setItem(22, GuiItems.decorateMarketItem(s.unit, List.of(
                MarketText.action(s.orderSide == OrderSide.BUY ? "Покупка" : "Продажа",
                        s.orderSide == OrderSide.BUY ? MarketPalette.SUCCESS : MarketPalette.SELL),
                MarketText.labelValue("Цена", CurrencyText.format(s.price), MarketPalette.TEXT),
                MarketText.labelValue("Осталось", Integer.toString(s.quantity), MarketPalette.TEXT))));
        put(box, s, TRADE_BACK, button(MarketIcons.BACK, "Назад", "К Моему"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, TRADE_PRIMARY, button(MarketIcons.CANCEL, "Отменить заявку", "Остаток будет возвращён"),
                GuiAction.simple(GuiAction.Type.PREPARE_CANCEL));
        openBox(player, s, box, "Биржа ValorCraft");
    }

    private void renderCancel(ServerPlayer player, MarketSession s) {
        SimpleContainer box = blank();
        s.resetActions();
        box.setItem(22, GuiItems.namedButton(new ItemStack(Items.BARRIER),
                MarketText.action("Отменить заявку?", MarketPalette.ERROR),
                List.of(MarketText.text("Остаток заявки будет возвращён."),
                        MarketText.muted("Предметы появятся в «Моём»."))));
        put(box, s, TRADE_BACK, button(MarketIcons.BACK, "Назад", "Не отменять"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, TRADE_PRIMARY, button(MarketIcons.CANCEL,
                MarketText.action("✕ Отменить заявку", MarketPalette.ERROR),
                List.of(MarketText.muted("Остаток будет возвращён"))), GuiAction.simple(GuiAction.Type.CONFIRM_CANCEL));
        openBox(player, s, box, "Биржа ValorCraft");
    }

    private void confirmCancel(ServerPlayer player, MarketSession s) {
        if (s.pendingCancelId == null) return;
        AuctionService.Outcome outcome = service().cancel(player.getUUID(), s.pendingCancelId, "market-gui");
        if (outcome.isSuccess()) MarketSounds.cancel(player); else MarketSounds.error(player);
        showOutcome(player, outcome);
        s.pendingCancelId = null;
        s.screen = MarketScreen.MY;
        renderMy(player, s);
    }

    private void claim(ServerPlayer player, MarketSession s, long deliveryId) {
        AuctionService.Outcome outcome = service().claimDelivery(player.getUUID(), deliveryId);
        if (outcome.isSuccess()) MarketSounds.claim(player); else MarketSounds.error(player);
        showOutcome(player, outcome);
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
            case MY -> renderMy(player, s);
            case TRADE_IMMEDIATE -> renderImmediateQuote(player, s);
            case TRADE_LIMIT -> renderEditor(player, s);
            default -> renderMarkets(player, s);
        }
    }

    private void openBox(ServerPlayer player, MarketSession s, SimpleContainer box, String title) {
        if (s.menu != null && s.contents != null && player.containerMenu == s.menu) {
            for (int slot = 0; slot < 54; slot++) s.contents.setItem(slot, box.getItem(slot));
            fullSync(player, s.menu);
            return;
        }
        s.transitioning = true;
        try {
            player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
                s.containerId = id;
                s.contents = box;
                s.menu = new ServerChestMenu(id, inventory, box, this, s);
                return s.menu;
            }, MarketText.colored("Биржа ValorCraft", MarketPalette.BRAND)));
        } finally {
            s.transitioning = false;
        }
        if (s.menu != null) fullSync(player, s.menu);
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

    private static SimpleContainer blank() {
        return new SimpleContainer(54);
    }

    private static void catalogueNavigation(SimpleContainer box, MarketSession s, Page<?> page) {
        pageEdges(box, s, page);
        put(box, s, NAV_SEARCH, button(MarketIcons.SEARCH, "Поиск", "Искать по названию"),
                GuiAction.simple(GuiAction.Type.SEARCH_HELP));
        catalogueInfo(box, s, page);
        put(box, s, NAV_MY, button(MarketIcons.MY, "Моё", "Заявки, покупки и возвраты"),
                GuiAction.simple(GuiAction.Type.MY));
    }

    private static void searchNavigation(SimpleContainer box, MarketSession s, Page<?> page) {
        pageEdges(box, s, page);
        put(box, s, NAV_SEARCH, button(MarketIcons.SEARCH, "Новый поиск", "Уточнить запрос"),
                GuiAction.simple(GuiAction.Type.SEARCH_HELP));
        catalogueInfo(box, s, page);
        put(box, s, NAV_MY, button(MarketIcons.CATALOGUE, "Все товары", "Вернуться к каталогу"),
                GuiAction.simple(GuiAction.Type.BROWSE));
    }

    private static void myNavigation(SimpleContainer box, MarketSession s, Page<?> page) {
        pageEdges(box, s, page);
        myInfo(box, s, page);
        put(box, s, NAV_MY, button(MarketIcons.CATALOGUE, "Каталог", "Все товары"),
                GuiAction.simple(GuiAction.Type.HOME));
    }

    private static void pageEdges(SimpleContainer box, MarketSession s, Page<?> page) {
        if (page.hasPrevious()) put(box, s, NAV_PREVIOUS,
                button(MarketIcons.PAGE_PREVIOUS, "← Предыдущая", ""),
                GuiAction.number(GuiAction.Type.PAGE, -1));
        if (page.hasNext()) put(box, s, NAV_NEXT,
                button(MarketIcons.PAGE_NEXT, "Следующая →", ""),
                GuiAction.number(GuiAction.Type.PAGE, 1));
    }

    /** Slot 49 — central information item: controls hint plus page counter. Never clickable. */
    private static void catalogueInfo(SimpleContainer box, MarketSession s, Page<?> page) {
        boolean multi = page.totalPages() > 1;
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        if (multi) {
            lore.add(MarketText.labelValue("Страница", (page.page() + 1) + " / " + page.totalPages(),
                    MarketPalette.TEXT));
            lore.add(MarketText.muted("ЛКМ → купить · ПКМ → продать"));
        } else {
            lore.add(MarketText.text("Биржа"));
            lore.add(MarketText.muted("ЛКМ по товару → купить"));
            lore.add(MarketText.muted("ПКМ по товару → продать"));
        }
        put(box, s, NAV_INFO, GuiItems.namedButton(new ItemStack(MarketIcons.INFO_BOOK),
                MarketText.colored("Биржа", MarketPalette.BRAND), lore), null);
    }

    /** Slot 49 on the «Моё» screen — page counter or a short description. Never clickable. */
    private static void myInfo(SimpleContainer box, MarketSession s, Page<?> page) {
        boolean multi = page.totalPages() > 1;
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        if (multi) {
            lore.add(MarketText.labelValue("Страница", (page.page() + 1) + " / " + page.totalPages(),
                    MarketPalette.TEXT));
        } else {
            lore.add(MarketText.text("Моё"));
        }
        lore.add(MarketText.muted("Заявки, покупки и возвраты"));
        put(box, s, NAV_INFO, GuiItems.namedButton(new ItemStack(MarketIcons.INFO_BOOK),
                MarketText.colored("Моё", MarketPalette.BRAND), lore), null);
    }

    private static void put(SimpleContainer box, MarketSession s, int slot, ItemStack item, GuiAction action) {
        box.setItem(slot, item.copy());
        if (action != null) s.actions.put(slot, action);
    }

    private static ItemStack button(net.minecraft.world.item.Item item, String name, String lore) {
        return GuiItems.namedButton(new ItemStack(item), MarketText.colored(name, MarketPalette.BRAND),
                lore == null || lore.isBlank() ? List.of() : List.of(MarketText.muted(lore)));
    }

    private static ItemStack button(net.minecraft.world.item.Item item, Component name, List<Component> lore) {
        return GuiItems.namedButton(new ItemStack(item), name, lore);
    }

    private static void quantityControls(SimpleContainer box, MarketSession s, OrderSide side) {
        if (side == OrderSide.BUY) {
            quantityPreset(box, s, 21, 1);
            quantityPreset(box, s, 22, 64);
        } else {
            quantityPreset(box, s, 21, 1);
            put(box, s, 22, button(MarketIcons.ALL, MarketText.action("Всё", MarketPalette.SELL),
                    List.of(MarketText.muted("Всё доступное сейчас"))), GuiAction.simple(GuiAction.Type.SET_MAX_QUANTITY));
        }
        put(box, s, 23, button(MarketIcons.EXACT, MarketText.action("Другое", MarketPalette.TEXT),
                List.of(MarketText.muted("Указать своё количество"))),
                GuiAction.simple(GuiAction.Type.EXACT_QUANTITY));
    }

    private static void quantityPreset(SimpleContainer box, MarketSession s, int slot, int quantity) {
        ItemStack icon = GuiItems.namedButton(new ItemStack(Items.PAPER),
                MarketText.colored(quantity == 1 ? "1 шт." : Integer.toString(quantity), MarketPalette.BRAND),
                List.of(MarketText.muted("Установить количество")));
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
        return amount <= 0 ? "—" : CurrencyText.format(amount);
    }

    private static String moneyOrUnavailable(long amount) {
        return amount <= 0 ? "нет предложений" : CurrencyText.format(amount);
    }

    private static void showOutcome(ServerPlayer player, AuctionService.Outcome outcome) {
        if (outcome.status() == AuctionService.Result.ACCEPTED_PENDING) {
            tell(player, "⏱ Заявка принята и безопасно завершается.", ChatFormatting.YELLOW);
        } else if (outcome.isSuccess()) {
            if (outcome.order() == null) {
                tell(player, "✓ Готово", ChatFormatting.GREEN);
            } else if (outcome.order().status() == OrderStatus.CANCELLED) {
                tell(player, "✓ Заявка отменена; возврат доступен в «Моём».", ChatFormatting.GREEN);
            } else if (outcome.order().remainingQuantity() == 0) {
                tell(player, "✓ Заявка исполнена полностью.", ChatFormatting.GREEN);
            } else if (outcome.filledQuantity() > 0) {
                tell(player, "Исполнено " + outcome.filledQuantity() + ", осталось "
                        + outcome.order().remainingQuantity() + "; заявка продолжает ждать.",
                        ChatFormatting.GREEN);
            } else {
                tell(player, "✓ Заявка создана и ждёт подходящего предложения.", ChatFormatting.GREEN);
            }
        } else {
            String friendly = switch (outcome.status()) {
                case INSUFFICIENT_FUNDS -> "Недостаточно средств.";
                case INSUFFICIENT_ITEMS -> "Недостаточно подходящих предметов.";
                case INVENTORY_FULL -> "Освободите место в инвентаре.";
                case NOT_YOUR_ORDER -> "Эта запись принадлежит другому игроку.";
                case ORDER_NOT_FOUND -> "Запись уже изменилась. Список обновлён.";
                case INVALID_PRICE, INVALID_QUANTITY -> "Проверьте цену и количество.";
                case OVER_LIMIT -> "Достигнут лимит активных заявок.";
                case BLACKLISTED -> "Этот предмет запрещён на бирже.";
                case MARKET_DISABLED -> "Биржа сейчас отключена.";
                default -> "Операцию не удалось завершить. Попробуйте позже.";
            };
            tell(player, "✕ " + friendly, ChatFormatting.RED);
        }
    }

    private static void onboarding(ServerPlayer player) {
        player.sendSystemMessage(MarketText.brand());
        player.sendSystemMessage(MarketText.text("ЛКМ по товару — купить, ПКМ — продать."));
        player.sendSystemMessage(Component.literal("[Помощь]")
                .withStyle(style -> style.withColor(MarketPalette.INFO)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah help"))));
    }

    private static void tutorial(ServerPlayer player) {
        player.sendSystemMessage(MarketText.brand());
        player.sendSystemMessage(MarketText.muted("«Купить/Продать сейчас» использует текущие предложения."));
        player.sendSystemMessage(MarketText.muted("«Своя цена» создаёт заявку, которая будет ждать игрока."));
        player.sendSystemMessage(Component.literal("[Подробные команды]")
                .withStyle(style -> style.withColor(MarketPalette.INFO)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah help"))));
    }

    private static void searchHelp(ServerPlayer player) {
        player.sendSystemMessage(MarketText.brand());
        player.sendSystemMessage(MarketText.muted("Поиск по названию предмета:"));
        player.sendSystemMessage(Component.literal("[/ah search ...]")
                        .withStyle(style -> style.withColor(MarketPalette.INFO)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                        "/ah search "))));
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    private static boolean ready(ServerPlayer player) {
        if (!VAuctionCore.instance().isRunning()) {
            tell(player, "Биржа ещё не готова или отключена.", ChatFormatting.RED);
            return false;
        }
        return true;
    }

    private static AuctionReadService read() { return VAuctionCore.instance().auctionReadService(); }
    private static AuctionService service() { return VAuctionCore.instance().auctionService(); }
}

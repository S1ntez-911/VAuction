package com.valorcraft.vauction.gui;

import com.valorcraft.vauction.application.AuctionReadService;
import com.valorcraft.vauction.application.AuctionService;
import com.valorcraft.vauction.application.Page;
import com.valorcraft.vauction.bootstrap.VAuctionCore;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.market.MarketCard;
import com.valorcraft.vauction.domain.market.MarketSummary;
import com.valorcraft.vauction.domain.market.OrderBookLevel;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
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
    private static final int[] CARD_SLOTS = {
            10,11,12,13,14,15,16, 19,20,21,22,23,24,25,
            28,29,30,31,32,33,34, 37,38,39,40,41,42,43
    };
    private static final MarketController INSTANCE = new MarketController();

    private final Map<UUID, MarketSession> sessions = new ConcurrentHashMap<>();

    private MarketController() {}

    public static MarketController instance() {
        return INSTANCE;
    }

    public void open(ServerPlayer player) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.HOME;
        session.page = 0;
        session.search = "";
        if (VAuctionCore.instance().notificationService().firstMarketOpen(player.getUUID())) {
            onboarding(player);
        }
        renderHome(player, session);
    }

    public void search(ServerPlayer player, String query, int page) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.SEARCH;
        session.search = query == null ? "" : query.trim();
        session.page = Math.max(0, page);
        renderMarkets(player, session);
    }

    public void openOrders(ServerPlayer player) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.MY_ORDERS;
        session.page = 0;
        renderOrders(player, session);
    }

    public void openDeliveries(ServerPlayer player) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.DELIVERIES;
        session.page = 0;
        renderDeliveries(player, session);
    }

    public boolean setQuantity(ServerPlayer player, int quantity) {
        MarketSession session = sessions.get(player.getUUID());
        if (session == null || (session.screen != MarketScreen.EDIT_ORDER
                && session.screen != MarketScreen.CONFIRM_ORDER)) return false;
        if (quantity <= 0) return false;
        session.quantity = quantity;
        session.screen = MarketScreen.EDIT_ORDER;
        renderEditor(player, session);
        return true;
    }

    public boolean setPrice(ServerPlayer player, long price) {
        MarketSession session = sessions.get(player.getUUID());
        if (session == null || (session.screen != MarketScreen.EDIT_ORDER
                && session.screen != MarketScreen.CONFIRM_ORDER)) return false;
        if (price <= 0) return false;
        session.price = price;
        session.screen = MarketScreen.EDIT_ORDER;
        renderEditor(player, session);
        return true;
    }

    public void clicked(Player rawPlayer, MarketSession session, int slotId,
                        int button, ClickType clickType) {
        if (!(rawPlayer instanceof ServerPlayer player)
                || !player.getUUID().equals(session.playerId) || session.executing) return;
        // Only ordinary left/right pickup clicks can invoke actions. QUICK_MOVE, SWAP,
        // CLONE, THROW, QUICK_CRAFT and PICKUP_ALL are all rejected without mutation.
        if (clickType != ClickType.PICKUP || (button != 0 && button != 1)) return;
        if (session.screen == MarketScreen.PICKER && slotId >= 54 && slotId <= 89) {
            int inventorySlot = slotId <= 80 ? slotId - 54 + 9 : slotId - 81;
            selectInventoryItem(player, session, inventorySlot);
            return;
        }
        if (slotId < 0 || slotId >= 54) return;
        GuiAction action = session.actions.get(slotId);
        if (action == null) return;
        session.executing = true;
        try {
            handle(player, session, action);
        } catch (RuntimeException e) {
            LOGGER.error("Market GUI action failed: player={}, screen={}, action={}",
                    player.getUUID(), session.screen, action.type(), e);
            tell(player, "Биржа временно недоступна. Попробуйте ещё раз.", ChatFormatting.RED);
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
    }

    public void clear() {
        sessions.clear();
    }

    private void handle(ServerPlayer player, MarketSession s, GuiAction a) {
        switch (a.type()) {
            case HOME -> { s.screen = MarketScreen.HOME; s.page = 0; s.search = "";
                s.orderSide = null; s.inventorySlot = -1; renderHome(player, s); }
            case BROWSE -> { s.screen = MarketScreen.BROWSE; s.page = 0; s.search = ""; renderMarkets(player, s); }
            case HELP -> tutorial(player);
            case SEARCH_HELP -> tell(player, "Поиск: /market search <название или id>", ChatFormatting.AQUA);
            case PICKER -> { s.screen = MarketScreen.PICKER; s.page = 0; s.orderSide = OrderSide.SELL;
                s.inventorySlot = -1; renderPicker(player, s); }
            case ORDERS -> { s.screen = MarketScreen.MY_ORDERS; s.page = 0; renderOrders(player, s); }
            case DELIVERIES -> { s.screen = MarketScreen.DELIVERIES; s.page = 0; renderDeliveries(player, s); }
            case OPEN_MARKET -> { s.orderSide = null; openMarket(player, s, a.item(), -1); }
            case PAGE -> { s.page = Math.max(0, s.page + a.number()); refreshCurrent(player, s); }
            case REFRESH -> refreshCurrent(player, s);
            case BUY -> beginOrder(player, s, OrderSide.BUY);
            case SELL -> beginOrder(player, s, OrderSide.SELL);
            case BUY_NOW -> beginImmediate(player, s, OrderSide.BUY);
            case SELL_NOW -> beginImmediate(player, s, OrderSide.SELL);
            case ADJUST_QUANTITY -> { s.quantity = clampQuantity(s.quantity, a.number());
                if (s.immediate) renderImmediateQuote(player, s); else renderEditor(player, s); }
            case ADJUST_PRICE_PERCENT -> { s.price = adjustedPrice(s.price, a.number()); renderEditor(player, s); }
            case BEST_PRICE -> { applyBestPrice(s); renderEditor(player, s); }
            case REVIEW -> review(player, s);
            case CONFIRM_IMMEDIATE -> confirmImmediate(player, s);
            case CONFIRM_ORDER -> confirmOrder(player, s);
            case PREPARE_CANCEL -> { s.pendingCancelId = a.orderId(); s.screen = MarketScreen.CONFIRM_CANCEL; renderCancel(player, s); }
            case CONFIRM_CANCEL -> confirmCancel(player, s);
            case CLAIM -> claim(player, s, a.deliveryId());
            case BACK -> back(player, s);
        }
    }

    private void renderHome(ServerPlayer player, MarketSession s) {
        SimpleContainer box = blank();
        s.resetActions();
        box.setItem(4, GuiItems.named(new ItemStack(Items.EMERALD), "Биржа ресурсов",
                ChatFormatting.GOLD, "Игроки покупают и продают ресурсы друг другу.",
                "Цена совпала — сделка происходит автоматически."));
        put(box, s, 11, button(Items.EMERALD_BLOCK, "Купить", "Выбрать товар и способ покупки"),
                GuiAction.simple(GuiAction.Type.BROWSE));
        put(box, s, 15, button(Items.CHEST, "Продать", "Выбрать точный предмет из инвентаря"),
                GuiAction.simple(GuiAction.Type.PICKER));
        put(box, s, 29, button(Items.WRITABLE_BOOK, "Мои заявки", "Проверить ожидание или отменить"),
                GuiAction.simple(GuiAction.Type.ORDERS));
        put(box, s, 33, button(Items.ENDER_CHEST, "Получить предметы", "Покупки и возвраты"),
                GuiAction.simple(GuiAction.Type.DELIVERIES));
        put(box, s, 39, button(Items.COMPASS, "Поиск", "Команда: /ah search <название>"),
                GuiAction.simple(GuiAction.Type.SEARCH_HELP));
        put(box, s, 41, button(Items.BOOK, "Помощь", "Короткое объяснение биржи"),
                GuiAction.simple(GuiAction.Type.HELP));

        ItemStack hand = player.getMainHandItem();
        if (!hand.isEmpty()) {
            ItemStack unit = hand.copy();
            unit.setCount(1);
            AuctionReadService.MarketView view = read().market(unit);
            if (view != null) {
                MarketSummary m = view.card().summary();
                int available = service().availableCount(player.getUUID(), unit);
                ItemStack context = GuiItems.named(unit, "Предмет в руке: " + hand.getHoverName().getString(),
                        ChatFormatting.AQUA, "У вас всего: " + available,
                        "Покупают от: " + moneyOrDash(m.bestBid()),
                        "Продают от: " + moneyOrDash(m.bestAsk()),
                        "Последняя сделка: " + moneyOrDash(m.lastTradePrice()),
                        "Нажмите, чтобы открыть рынок");
                put(box, s, 22, context, GuiAction.market(unit));
            }
        } else {
            box.setItem(22, GuiItems.named(new ItemStack(Items.AIR), "Предмет в руке",
                    ChatFormatting.GRAY, "Возьмите предмет в руку для быстрого доступа к его рынку."));
        }
        openBox(player, s, box, "Биржа ресурсов");
    }

    private void renderMarkets(ServerPlayer player, MarketSession s) {
        Page<MarketCard> page = read().markets(s.page, s.screen == MarketScreen.SEARCH ? s.search : "");
        SimpleContainer box = blank();
        s.resetActions();
        int i = 0;
        for (MarketCard card : page.items()) {
            ItemStack visual = read().visual(card.visual());
            if (visual.isEmpty()) visual = new ItemStack(Items.BARRIER);
            MarketSummary m = card.summary();
            ItemStack icon = GuiItems.named(visual, m.displayItem(), ChatFormatting.GOLD,
                    "Продажа: " + moneyOrDash(m.bestAsk()),
                    "Покупка: " + moneyOrDash(m.bestBid()),
                    "Предложено: " + m.availableSellQuantity(),
                    "Хотят купить: " + m.availableBuyQuantity(),
                    "Последняя сделка: " + moneyOrDash(m.lastTradePrice()),
                    "Нажмите, чтобы открыть");
            put(box, s, CARD_SLOTS[i++], icon, GuiAction.market(visual));
        }
        if (page.items().isEmpty() && s.screen == MarketScreen.SEARCH) {
            tell(player, "По запросу «" + s.search + "» ничего не найдено.", ChatFormatting.YELLOW);
        }
        put(box, s, 45, button(Items.ARROW, "На главную", "К основным действиям"),
                GuiAction.simple(GuiAction.Type.HOME));
        put(box, s, 48, button(Items.WRITABLE_BOOK, "Мои заявки", "Просмотр и отмена"),
                GuiAction.simple(GuiAction.Type.ORDERS));
        put(box, s, 50, button(Items.ENDER_CHEST, "Получить предметы", "Забрать покупки и возвраты"),
                GuiAction.simple(GuiAction.Type.DELIVERIES));
        if (page.hasPrevious()) put(box, s, 52, button(Items.ARROW, "Предыдущая", "Страница " + s.page),
                GuiAction.number(GuiAction.Type.PAGE, -1));
        if (page.hasNext()) put(box, s, 53, button(Items.ARROW, "Следующая", "Страница " + (s.page + 2)),
                GuiAction.number(GuiAction.Type.PAGE, 1));
        String title = s.screen == MarketScreen.SEARCH
                ? "Поиск: " + shorten(s.search, 20) : "Биржа ресурсов";
        openBox(player, s, box, title);
    }

    private void renderPicker(ServerPlayer player, MarketSession s) {
        SimpleContainer box = blank();
        s.resetActions();
        box.setItem(22, GuiItems.named(new ItemStack(Items.HOPPER), "Выберите предмет",
                ChatFormatting.AQUA, "Нажмите предмет в своём инвентаре ниже.",
                "Он не будет перемещён или изменён."));
        put(box, s, 49, button(Items.ARROW, "Назад", "На главную"), GuiAction.simple(GuiAction.Type.HOME));
        openBox(player, s, box, "Выбор предмета");
    }

    private void selectInventoryItem(ServerPlayer player, MarketSession s, int slot) {
        if (slot < 0 || slot >= player.getInventory().getContainerSize()) return;
        ItemStack current = player.getInventory().getItem(slot);
        if (current.isEmpty()) {
            tell(player, "Выберите непустой слот.", ChatFormatting.RED);
            return;
        }
        openMarket(player, s, current.copy(), slot);
        if (s.orderSide == OrderSide.SELL) beginImmediate(player, s, OrderSide.SELL);
    }

    private void openMarket(ServerPlayer player, MarketSession s, ItemStack unit, int inventorySlot) {
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
        s.screen = MarketScreen.MARKET;
        renderMarket(player, s);
    }

    private void renderMarket(ServerPlayer player, MarketSession s) {
        AuctionReadService.MarketView view = read().market(s.unit);
        if (view == null || !view.card().summary().marketKey().equals(s.marketKey)) {
            tell(player, "Рынок изменился. Откройте предмет заново.", ChatFormatting.YELLOW);
            s.screen = MarketScreen.HOME;
            renderHome(player, s);
            return;
        }
        SimpleContainer box = blank();
        s.resetActions();
        MarketSummary m = view.card().summary();
        int available = service().availableCount(player.getUUID(), s.unit);
        box.setItem(4, GuiItems.named(s.unit, m.displayItem(), ChatFormatting.GOLD,
                "Лучшая продажа: " + moneyOrDash(m.bestAsk()),
                "Лучшая покупка: " + moneyOrDash(m.bestBid()),
                "Последняя сделка: " + moneyOrDash(m.lastTradePrice()),
                "У вас точных предметов: " + available));
        levelItems(box, view.sells(), 10, ChatFormatting.RED, "Продают");
        levelItems(box, view.buys(), 28, ChatFormatting.GREEN, "Покупают");
        put(box, s, 45, button(Items.ARROW, "Назад", "К главной биржи"), GuiAction.simple(GuiAction.Type.HOME));
        put(box, s, 46, button(Items.LIME_CONCRETE, "Купить сейчас", "Показать точную цену по текущим продавцам"), GuiAction.simple(GuiAction.Type.BUY_NOW));
        put(box, s, 47, button(Items.RED_CONCRETE, "Продать сейчас", "Продать текущим покупателям; доступно: " + available), GuiAction.simple(GuiAction.Type.SELL_NOW));
        put(box, s, 49, button(Items.CLOCK, "Обновить", "Обновление только по нажатию"), GuiAction.simple(GuiAction.Type.REFRESH));
        put(box, s, 50, button(Items.EMERALD, "Заявка на покупку", "Указать свою цену и ждать продавца"), GuiAction.simple(GuiAction.Type.BUY));
        put(box, s, 51, button(Items.CHEST, "Заявка на продажу", "Указать свою цену и ждать покупателя"), GuiAction.simple(GuiAction.Type.SELL));
        put(box, s, 53, button(Items.WRITABLE_BOOK, "Мои заявки", "Просмотр и отмена"), GuiAction.simple(GuiAction.Type.ORDERS));
        openBox(player, s, box, "Рынок: " + shorten(m.displayItem(), 20));
    }

    private void beginImmediate(ServerPlayer player, MarketSession s, OrderSide side) {
        int available = side == OrderSide.SELL ? service().availableCount(player.getUUID(), s.unit) : 0;
        if (side == OrderSide.SELL && available <= 0) {
            tell(player, "В инвентаре нет точно такого предмета. Возьмите его в руку или выберите другой рынок.",
                    ChatFormatting.RED);
            return;
        }
        s.orderSide = side;
        s.immediate = true;
        s.quantity = side == OrderSide.SELL ? available : 1;
        s.pendingRequestId = UUID.randomUUID();
        s.screen = MarketScreen.QUOTE_NOW;
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
        s.screen = MarketScreen.QUOTE_NOW;
        SimpleContainer box = blank();
        s.resetActions();
        java.util.ArrayList<String> lore = new java.util.ArrayList<>();
        lore.add("Запрошено: " + quote.requestedQuantity());
        lore.add("Можно исполнить сейчас: " + quote.fillableQuantity() + " / " + quote.requestedQuantity());
        for (AuctionReadService.QuoteLevel level : quote.levels()) {
            lore.add(level.quantity() + " × " + CurrencyText.format(level.pricePerUnit()));
        }
        if (quote.executable()) {
            lore.add((s.orderSide == OrderSide.BUY ? "Итого: " : "До комиссии: ")
                    + CurrencyText.format(quote.expectedTotal()));
            lore.add("Средняя цена: " + quote.averagePrice().toPlainString());
            lore.add((s.orderSide == OrderSide.BUY ? "Не дороже: " : "Не дешевле: ")
                    + CurrencyText.format(quote.worstExecutionPrice()));
        } else {
            lore.add("Сейчас нет подходящих предложений.");
        }
        if (quote.insufficientLiquidity()) lore.add("Остаток не станет ожидающей заявкой.");
        box.setItem(13, GuiItems.named(s.unit,
                s.orderSide == OrderSide.BUY ? "Купить сейчас" : "Продать сейчас",
                quote.executable() ? ChatFormatting.GOLD : ChatFormatting.GRAY,
                lore.toArray(String[]::new)));
        put(box, s, 20, button(Items.RED_DYE, "-8", "Уменьшить количество"), GuiAction.number(GuiAction.Type.ADJUST_QUANTITY, -8));
        put(box, s, 21, button(Items.RED_DYE, "-1", "Уменьшить количество"), GuiAction.number(GuiAction.Type.ADJUST_QUANTITY, -1));
        put(box, s, 23, button(Items.LIME_DYE, "+1", "Увеличить количество"), GuiAction.number(GuiAction.Type.ADJUST_QUANTITY, 1));
        put(box, s, 24, button(Items.LIME_DYE, "+64", "Увеличить количество"), GuiAction.number(GuiAction.Type.ADJUST_QUANTITY, 64));
        put(box, s, 45, button(Items.ARROW, "Назад", "К рынку"), GuiAction.simple(GuiAction.Type.BACK));
        if (quote.executable()) {
            put(box, s, 49, button(Items.LIME_CONCRETE, "Подтвердить",
                    s.orderSide == OrderSide.BUY
                            ? "Купить не дороже подтверждённой цены"
                            : "Продать не дешевле подтверждённой цены"),
                    GuiAction.simple(GuiAction.Type.CONFIRM_IMMEDIATE));
        }
        openBox(player, s, box, "Биржа ресурсов");
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
        if (!outcome.isSuccess()) {
            showOutcome(player, outcome);
        } else if (filled == 0) {
            tell(player, s.orderSide == OrderSide.BUY
                            ? "Рынок изменился: по подтверждённой цене ничего не куплено. Средства освобождены."
                            : "Рынок изменился: по подтверждённой цене ничего не продано. Предметы доступны в получениях.",
                    ChatFormatting.YELLOW);
        } else if (s.orderSide == OrderSide.BUY) {
            tell(player, "Куплено: " + filled + " " + s.unit.getHoverName().getString()
                    + ". Предметы готовы к получению.", ChatFormatting.GREEN);
        } else {
            tell(player, "Продано: " + filled + " " + s.unit.getHoverName().getString()
                    + ". Неисполненный остаток доступен в получениях.", ChatFormatting.GREEN);
        }
        s.pendingRequestId = null;
        s.immediate = false;
        if (s.orderSide == OrderSide.BUY || filled < quote.fillableQuantity()) {
            s.screen = MarketScreen.DELIVERIES;
            s.page = 0;
            renderDeliveries(player, s);
        } else {
            s.screen = MarketScreen.MARKET;
            renderMarket(player, s);
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
        s.quantity = side == OrderSide.SELL ? available : 1;
        AuctionReadService.MarketView view = read().market(s.unit);
        MarketSummary m = view == null ? null : view.card().summary();
        long preferred = side == OrderSide.BUY
                ? (m == null ? 0 : m.bestAsk()) : (m == null ? 0 : m.bestBid());
        if (preferred <= 0 && m != null) preferred = m.lastTradePrice();
        s.price = Math.max(1, preferred);
        s.pendingRequestId = UUID.randomUUID();
        s.screen = MarketScreen.EDIT_ORDER;
        renderEditor(player, s);
    }

    private void renderEditor(ServerPlayer player, MarketSession s) {
        SimpleContainer box = blank();
        s.resetActions();
        box.setItem(13, GuiItems.named(s.unit,
                s.orderSide == OrderSide.BUY ? "Покупка" : "Продажа", ChatFormatting.GOLD,
                "Количество: " + s.quantity,
                "Цена за единицу: " + CurrencyText.format(s.price),
                s.orderSide == OrderSide.SELL
                        ? "Доступно точных предметов: " + service().availableCount(player.getUUID(), s.unit)
                        : "Средства резервируются после подтверждения"));
        put(box, s, 20, button(Items.RED_DYE, "-8", "Уменьшить количество"), GuiAction.number(GuiAction.Type.ADJUST_QUANTITY, -8));
        put(box, s, 21, button(Items.RED_DYE, "-1", "Уменьшить количество"), GuiAction.number(GuiAction.Type.ADJUST_QUANTITY, -1));
        put(box, s, 23, button(Items.LIME_DYE, "+1", "Увеличить количество"), GuiAction.number(GuiAction.Type.ADJUST_QUANTITY, 1));
        put(box, s, 24, button(Items.LIME_DYE, "+64", "Увеличить количество"), GuiAction.number(GuiAction.Type.ADJUST_QUANTITY, 64));
        put(box, s, 29, button(Items.REDSTONE, "Цена -10%", "Минимум 1"), GuiAction.number(GuiAction.Type.ADJUST_PRICE_PERCENT, -10));
        put(box, s, 30, button(Items.REDSTONE, "Цена -1%", "Минимум 1"), GuiAction.number(GuiAction.Type.ADJUST_PRICE_PERCENT, -1));
        put(box, s, 31, button(Items.COMPARATOR, "Лучшая цена", "Подставить текущую лучшую цену"), GuiAction.simple(GuiAction.Type.BEST_PRICE));
        put(box, s, 32, button(Items.GLOWSTONE_DUST, "Цена +1%", "Увеличить цену"), GuiAction.number(GuiAction.Type.ADJUST_PRICE_PERCENT, 1));
        put(box, s, 33, button(Items.GLOWSTONE_DUST, "Цена +10%", "Увеличить цену"), GuiAction.number(GuiAction.Type.ADJUST_PRICE_PERCENT, 10));
        put(box, s, 45, button(Items.ARROW, "Назад", "К рынку"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, 49, button(Items.WRITABLE_BOOK, "Проверить", "Перейти к подтверждению"), GuiAction.simple(GuiAction.Type.REVIEW));
        openBox(player, s, box, s.orderSide == OrderSide.BUY ? "Новая покупка" : "Новая продажа");
    }

    private void review(ServerPlayer player, MarketSession s) {
        try {
            Math.multiplyExact(s.price, (long) s.quantity);
        } catch (ArithmeticException e) {
            tell(player, "Сумма слишком велика.", ChatFormatting.RED);
            return;
        }
        s.screen = MarketScreen.CONFIRM_ORDER;
        SimpleContainer box = blank();
        s.resetActions();
        long total = s.price * (long) s.quantity;
        AuctionReadService.MarketView currentView = read().market(s.unit);
        MarketSummary currentMarket = currentView == null ? null : currentView.card().summary();
        boolean warning = shouldWarnPrice(s.orderSide, s.price, currentMarket);
        String[] normalLines = s.orderSide == OrderSide.BUY
                ? new String[]{"Количество: " + s.quantity, "Максимум за единицу: " + CurrencyText.format(s.price),
                "Будет зарезервировано: " + CurrencyText.format(total),
                "Если исполнится дешевле, разница не списывается."}
                : new String[]{"Количество: " + s.quantity, "Цена за единицу: " + CurrencyText.format(s.price),
                "Предметы будут сняты сервером после подтверждения."};
        String[] lines = warning ? new String[]{"⚠ Цена сильно отличается от текущего рынка.",
                "Ориентир: " + CurrencyText.format(referencePrice(currentMarket)),
                "Вы указали: " + CurrencyText.format(s.price),
                "Цена не изменена. Проверьте лишний ноль."} : normalLines;
        box.setItem(22, GuiItems.named(s.unit, warning ? "Проверьте цену" : "Проверьте заявку",
                warning ? ChatFormatting.YELLOW : ChatFormatting.GOLD, lines));
        put(box, s, 45, button(Items.ARROW, warning ? "Изменить цену" : "Назад", "Вернуться к параметрам"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, 49, button(warning ? Items.YELLOW_CONCRETE : Items.LIME_CONCRETE,
                warning ? "Всё равно продолжить" : "Подтвердить", "Введённая цена останется без изменений"),
                GuiAction.simple(GuiAction.Type.CONFIRM_ORDER));
        openBox(player, s, box, "Подтверждение заявки");
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
        s.screen = MarketScreen.MY_ORDERS;
        s.page = 0;
        renderOrders(player, s);
    }

    private void renderOrders(ServerPlayer player, MarketSession s) {
        Page<Order> page = read().playerOrders(player.getUUID(), s.page);
        SimpleContainer box = blank();
        s.resetActions();
        int i = 0;
        for (Order order : page.items()) {
            ItemStack visual = read().visual(order.item());
            if (visual.isEmpty()) visual = new ItemStack(Items.BARRIER);
            String status = order.status() == OrderStatus.ACTIVE
                    ? (order.filledQuantity() > 0 ? "частично исполнена, ждёт продолжения" :
                    (order.side() == OrderSide.BUY ? "ожидает продавца" : "ожидает покупателя"))
                    : orderStatus(order.status());
            ItemStack icon = GuiItems.named(visual,
                    (order.side() == OrderSide.BUY ? "Покупка: " : "Продажа: ") + order.item().displayName(),
                    order.status() == OrderStatus.MANUAL_REVIEW ? ChatFormatting.RED : ChatFormatting.GOLD,
                    "Цена: " + CurrencyText.format(order.pricePerUnit()),
                    "Всего: " + order.originalQuantity(), "Исполнено: " + order.filledQuantity(),
                    "Осталось: " + order.remainingQuantity(), "Статус: " + status,
                    order.status() == OrderStatus.ACTIVE ? "Нажмите, чтобы отменить" : "ID: " + order.orderId());
            GuiAction action = order.status() == OrderStatus.ACTIVE
                    ? GuiAction.order(GuiAction.Type.PREPARE_CANCEL, order.orderId()) : null;
            put(box, s, CARD_SLOTS[i++], icon, action);
        }
        if (page.items().isEmpty()) {
            box.setItem(22, GuiItems.named(new ItemStack(Items.WRITABLE_BOOK), "У вас пока нет заявок",
                    ChatFormatting.GRAY, "Продажа: возьмите предмет в руку и используйте /ah sell <цена>.",
                    "Покупка: откройте биржу и выберите товар."));
        }
        put(box, s, 45, button(Items.ARROW, "На главную", "Вернуться к рынкам"), GuiAction.simple(GuiAction.Type.HOME));
        if (page.hasPrevious()) put(box, s, 52, button(Items.ARROW, "Предыдущая", ""), GuiAction.number(GuiAction.Type.PAGE, -1));
        if (page.hasNext()) put(box, s, 53, button(Items.ARROW, "Следующая", ""), GuiAction.number(GuiAction.Type.PAGE, 1));
        openBox(player, s, box, "Мои заявки");
    }

    private void renderCancel(ServerPlayer player, MarketSession s) {
        SimpleContainer box = blank();
        s.resetActions();
        box.setItem(22, GuiItems.named(new ItemStack(Items.BARRIER), "Отменить заявку?",
                ChatFormatting.RED, "Деньги за некупленный остаток вернутся.",
                "Непроданные предметы появятся в получениях."));
        put(box, s, 45, button(Items.ARROW, "Назад", "Не отменять"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, 49, button(Items.RED_CONCRETE, "Подтвердить отмену", "Действие необратимо"), GuiAction.simple(GuiAction.Type.CONFIRM_CANCEL));
        openBox(player, s, box, "Подтверждение отмены");
    }

    private void confirmCancel(ServerPlayer player, MarketSession s) {
        if (s.pendingCancelId == null) return;
        AuctionService.Outcome outcome = service().cancel(player.getUUID(), s.pendingCancelId, "market-gui");
        showOutcome(player, outcome);
        s.pendingCancelId = null;
        s.screen = MarketScreen.MY_ORDERS;
        renderOrders(player, s);
    }

    private void renderDeliveries(ServerPlayer player, MarketSession s) {
        Page<AuctionDelivery> page = read().deliveries(player.getUUID(), s.page);
        SimpleContainer box = blank();
        s.resetActions();
        int i = 0;
        for (AuctionDelivery delivery : page.items()) {
            ItemStack visual = read().visual(delivery.item());
            if (visual.isEmpty()) visual = new ItemStack(Items.BARRIER);
            ItemStack icon = GuiItems.named(visual, delivery.item().displayName(), ChatFormatting.AQUA,
                    "Количество: " + delivery.item().quantity(),
                    delivery.deliveryType() == com.valorcraft.vauction.domain.delivery.DeliveryType.PURCHASED
                            ? "Куплено на бирже" : "Возврат предметов из заявки",
                    "Нажмите, чтобы получить", "Получение #" + delivery.deliveryId());
            put(box, s, CARD_SLOTS[i++], icon, GuiAction.delivery(delivery.deliveryId()));
        }
        if (page.items().isEmpty()) {
            box.setItem(22, GuiItems.named(new ItemStack(Items.ENDER_CHEST), "Здесь пока пусто",
                    ChatFormatting.GRAY, "Купленные предметы и возвраты появятся здесь."));
        }
        put(box, s, 45, button(Items.ARROW, "На главную", "Вернуться к рынкам"), GuiAction.simple(GuiAction.Type.HOME));
        if (page.hasPrevious()) put(box, s, 52, button(Items.ARROW, "Предыдущая", ""), GuiAction.number(GuiAction.Type.PAGE, -1));
        if (page.hasNext()) put(box, s, 53, button(Items.ARROW, "Следующая", ""), GuiAction.number(GuiAction.Type.PAGE, 1));
        openBox(player, s, box, "Получить предметы");
    }

    private void claim(ServerPlayer player, MarketSession s, long deliveryId) {
        AuctionService.Outcome outcome = service().claimDelivery(player.getUUID(), deliveryId);
        showOutcome(player, outcome);
        renderDeliveries(player, s);
    }

    private void back(ServerPlayer player, MarketSession s) {
        if (s.screen == MarketScreen.QUOTE_NOW) {
            s.immediate = false;
            s.screen = MarketScreen.MARKET;
            renderMarket(player, s);
        } else if (s.screen == MarketScreen.CONFIRM_ORDER) {
            s.screen = MarketScreen.EDIT_ORDER;
            renderEditor(player, s);
        } else if (s.screen == MarketScreen.CONFIRM_CANCEL) {
            s.screen = MarketScreen.MY_ORDERS;
            renderOrders(player, s);
        } else {
            s.screen = MarketScreen.MARKET;
            renderMarket(player, s);
        }
    }

    private void refreshCurrent(ServerPlayer player, MarketSession s) {
        switch (s.screen) {
            case HOME -> renderHome(player, s);
            case BROWSE, SEARCH -> renderMarkets(player, s);
            case MARKET -> renderMarket(player, s);
            case MY_ORDERS -> renderOrders(player, s);
            case DELIVERIES -> renderDeliveries(player, s);
            default -> renderMarkets(player, s);
        }
    }

    private void applyBestPrice(MarketSession s) {
        AuctionReadService.MarketView view = read().market(s.unit);
        if (view == null) return;
        MarketSummary m = view.card().summary();
        long best = s.orderSide == OrderSide.BUY ? m.bestAsk() : m.bestBid();
        if (best <= 0) best = m.lastTradePrice();
        if (best > 0) s.price = best;
    }

    private void openBox(ServerPlayer player, MarketSession s, SimpleContainer box, String title) {
        if (s.menu != null && s.contents != null && player.containerMenu == s.menu) {
            for (int slot = 0; slot < 54; slot++) s.contents.setItem(slot, box.getItem(slot));
            s.menu.broadcastChanges();
            return;
        }
        s.transitioning = true;
        try {
            player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
                s.containerId = id;
                s.contents = box;
                s.menu = new ServerChestMenu(id, inventory, box, this, s);
                return s.menu;
            }, Component.literal("Биржа ресурсов")));
        } finally {
            s.transitioning = false;
        }
    }

    private static SimpleContainer blank() {
        return new SimpleContainer(54);
    }

    private static void levelItems(SimpleContainer box, List<OrderBookLevel> levels, int first,
                                   ChatFormatting color, String label) {
        for (int i = 0; i < levels.size() && i < 7; i++) {
            OrderBookLevel level = levels.get(i);
            ItemStack icon = new ItemStack(color == ChatFormatting.RED ? Items.RED_STAINED_GLASS_PANE
                    : Items.LIME_STAINED_GLASS_PANE);
            box.setItem(first + i, GuiItems.named(icon, label, color,
                    "Цена: " + CurrencyText.format(level.pricePerUnit()),
                    "Количество: " + level.quantity()));
        }
    }

    private static void put(SimpleContainer box, MarketSession s, int slot, ItemStack item, GuiAction action) {
        box.setItem(slot, item.copy());
        if (action != null) s.actions.put(slot, action);
    }

    private static ItemStack button(net.minecraft.world.item.Item item, String name, String lore) {
        return GuiItems.named(new ItemStack(item), name, ChatFormatting.YELLOW, lore);
    }

    private static int clampQuantity(int current, int delta) {
        long value = (long) current + delta;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, value));
    }

    private static long adjustedPrice(long current, int percent) {
        if (percent < 0) {
            long reduction = Math.max(1, current / Math.max(1, 100 / -percent));
            return Math.max(1, current - reduction);
        }
        long increase;
        try {
            increase = Math.max(1, Math.multiplyExact(current, percent) / 100);
            return Math.addExact(current, increase);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
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

    private static String orderStatus(OrderStatus status) {
        return switch (status) {
            case ACTIVE -> "активна";
            case FILLED -> "исполнена";
            case CANCELLED -> "отменена";
            case EXPIRED -> "истекла";
            case MANUAL_REVIEW -> "приостановлена, обратитесь к администратору";
            case LEGACY_LOCKED -> "перенесена из старой версии";
        };
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static void showOutcome(ServerPlayer player, AuctionService.Outcome outcome) {
        if (outcome.status() == AuctionService.Result.ACCEPTED_PENDING) {
            tell(player, "Заявка принята и безопасно завершается.", ChatFormatting.YELLOW);
        } else if (outcome.isSuccess()) {
            if (outcome.order() == null) {
                tell(player, "Готово.", ChatFormatting.GREEN);
            } else if (outcome.order().status() == OrderStatus.CANCELLED) {
                tell(player, "Заявка отменена; возврат доступен в получениях.", ChatFormatting.GREEN);
            } else if (outcome.order().remainingQuantity() == 0) {
                tell(player, "Заявка исполнена полностью.", ChatFormatting.GREEN);
            } else if (outcome.filledQuantity() > 0) {
                tell(player, "Исполнено " + outcome.filledQuantity() + ", осталось "
                        + outcome.order().remainingQuantity() + "; заявка продолжает ждать.",
                        ChatFormatting.GREEN);
            } else {
                tell(player, "Заявка создана и ждёт подходящего предложения.", ChatFormatting.GREEN);
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
            tell(player, friendly, ChatFormatting.RED);
        }
    }

    private static void onboarding(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Добро пожаловать на Биржу ресурсов. Здесь игроки покупают и продают ресурсы друг другу.")
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("[Открыть биржу]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah")))
                .append(Component.literal("  "))
                .append(Component.literal("[Помощь]")
                        .withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah help")))));
    }

    private static void tutorial(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Биржа ValorCraft").withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("«Купить/Продать сейчас» использует текущие предложения и никогда не оставляет остаток ждать.")
                .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("«Заявка» позволяет назначить свою цену и будет ждать другого игрока.")
                .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("[Подробные команды]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ah help"))));
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

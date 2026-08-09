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
        renderMarkets(player, session);
    }

    public void search(ServerPlayer player, String query, int page) {
        if (!ready(player)) return;
        MarketSession session = sessions.computeIfAbsent(player.getUUID(), MarketSession::new);
        session.screen = MarketScreen.SEARCH;
        session.search = query == null ? "" : query.trim();
        session.page = Math.max(0, page);
        renderMarkets(player, session);
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
                s.orderSide = null; s.inventorySlot = -1; renderMarkets(player, s); }
            case SEARCH_HELP -> tell(player, "Поиск: /market search <название или id>", ChatFormatting.AQUA);
            case PICKER -> { s.screen = MarketScreen.PICKER; s.page = 0; s.orderSide = null;
                s.inventorySlot = -1; renderPicker(player, s); }
            case ORDERS -> { s.screen = MarketScreen.MY_ORDERS; s.page = 0; renderOrders(player, s); }
            case DELIVERIES -> { s.screen = MarketScreen.DELIVERIES; s.page = 0; renderDeliveries(player, s); }
            case OPEN_MARKET -> { s.orderSide = null; openMarket(player, s, a.item(), -1); }
            case PAGE -> { s.page = Math.max(0, s.page + a.number()); refreshCurrent(player, s); }
            case REFRESH -> refreshCurrent(player, s);
            case BUY -> beginOrder(player, s, OrderSide.BUY);
            case SELL -> beginOrder(player, s, OrderSide.SELL);
            case ADJUST_QUANTITY -> { s.quantity = clampQuantity(s.quantity, a.number()); renderEditor(player, s); }
            case ADJUST_PRICE_PERCENT -> { s.price = adjustedPrice(s.price, a.number()); renderEditor(player, s); }
            case BEST_PRICE -> { applyBestPrice(s); renderEditor(player, s); }
            case REVIEW -> review(player, s);
            case CONFIRM_ORDER -> confirmOrder(player, s);
            case PREPARE_CANCEL -> { s.pendingCancelId = a.orderId(); s.screen = MarketScreen.CONFIRM_CANCEL; renderCancel(player, s); }
            case CONFIRM_CANCEL -> confirmCancel(player, s);
            case CLAIM -> claim(player, s, a.deliveryId());
            case BACK -> back(player, s);
        }
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
        put(box, s, 45, button(Items.COMPASS, "Поиск", "Используйте: /market search <текст>"),
                GuiAction.simple(GuiAction.Type.SEARCH_HELP));
        put(box, s, 46, button(Items.CHEST, "Выбрать из инвентаря", "Предмет не перемещается"),
                GuiAction.simple(GuiAction.Type.PICKER));
        put(box, s, 48, button(Items.WRITABLE_BOOK, "Мои заявки", "Просмотр и отмена"),
                GuiAction.simple(GuiAction.Type.ORDERS));
        put(box, s, 50, button(Items.ENDER_CHEST, "Доставки", "Забрать готовые предметы"),
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
        if (s.orderSide == OrderSide.SELL) beginOrder(player, s, OrderSide.SELL);
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
            renderMarkets(player, s);
            return;
        }
        SimpleContainer box = blank();
        s.resetActions();
        MarketSummary m = view.card().summary();
        box.setItem(4, GuiItems.named(s.unit, m.displayItem(), ChatFormatting.GOLD,
                "Лучшая продажа: " + moneyOrDash(m.bestAsk()),
                "Лучшая покупка: " + moneyOrDash(m.bestBid()),
                "Последняя сделка: " + moneyOrDash(m.lastTradePrice())));
        levelItems(box, view.sells(), 10, ChatFormatting.RED, "Продают");
        levelItems(box, view.buys(), 28, ChatFormatting.GREEN, "Покупают");
        put(box, s, 45, button(Items.ARROW, "Назад", "К списку рынков"), GuiAction.simple(GuiAction.Type.HOME));
        put(box, s, 47, button(Items.EMERALD, "Купить", "Создать заявку на покупку"), GuiAction.simple(GuiAction.Type.BUY));
        put(box, s, 49, button(Items.CLOCK, "Обновить", "Обновление только по нажатию"), GuiAction.simple(GuiAction.Type.REFRESH));
        put(box, s, 51, button(Items.CHEST, "Продать", "Предметы будут взяты только после подтверждения"), GuiAction.simple(GuiAction.Type.SELL));
        put(box, s, 53, button(Items.WRITABLE_BOOK, "Мои заявки", "Просмотр и отмена"), GuiAction.simple(GuiAction.Type.ORDERS));
        openBox(player, s, box, "Рынок: " + shorten(m.displayItem(), 20));
    }

    private void beginOrder(ServerPlayer player, MarketSession s, OrderSide side) {
        if (side == OrderSide.SELL && s.inventorySlot < 0) {
            s.orderSide = OrderSide.SELL;
            s.screen = MarketScreen.PICKER;
            renderPicker(player, s);
            return;
        }
        s.orderSide = side;
        s.quantity = 1;
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
                "Точно: /market quantity <число>",
                "Точно: /market price <минимальные единицы>"));
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
        String[] lines = s.orderSide == OrderSide.BUY
                ? new String[]{"Количество: " + s.quantity, "Максимум за единицу: " + CurrencyText.format(s.price),
                "Будет зарезервировано: " + CurrencyText.format(total),
                "Если исполнится дешевле, разница не списывается."}
                : new String[]{"Количество: " + s.quantity, "Цена за единицу: " + CurrencyText.format(s.price),
                "Предметы будут сняты сервером после подтверждения."};
        box.setItem(22, GuiItems.named(s.unit, "Проверьте заявку", ChatFormatting.GOLD, lines));
        put(box, s, 45, button(Items.ARROW, "Назад", "Изменить параметры"), GuiAction.simple(GuiAction.Type.BACK));
        put(box, s, 49, button(Items.LIME_CONCRETE, "Подтвердить", "Операция защищена от двойного клика"), GuiAction.simple(GuiAction.Type.CONFIRM_ORDER));
        openBox(player, s, box, "Подтверждение заявки");
    }

    private void confirmOrder(ServerPlayer player, MarketSession s) {
        if (s.pendingRequestId == null || s.quantity <= 0 || s.price <= 0) return;
        AuctionService.Outcome outcome;
        if (s.orderSide == OrderSide.BUY) {
            outcome = service().createBuyOrder(player.getUUID(), s.unit, s.price, s.quantity,
                    s.pendingRequestId);
        } else {
            if (s.inventorySlot < 0 || s.inventorySlot >= player.getInventory().getContainerSize()) {
                tell(player, "Выбранный слот больше недоступен.", ChatFormatting.RED);
                return;
            }
            ItemStack current = player.getInventory().getItem(s.inventorySlot);
            if (current.isEmpty() || !ItemStack.isSameItemSameTags(current, s.unit)
                    || !s.marketKey.equals(read().marketKey(current))) {
                tell(player, "Предмет в выбранном слоте изменился. Выберите его заново.", ChatFormatting.YELLOW);
                s.inventorySlot = -1;
                s.screen = MarketScreen.PICKER;
                renderPicker(player, s);
                return;
            }
            outcome = service().createSellOrderFromSlot(player, s.inventorySlot, s.price,
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
            String status = orderStatus(order.status());
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
        put(box, s, 45, button(Items.ARROW, "На главную", "Вернуться к рынкам"), GuiAction.simple(GuiAction.Type.HOME));
        if (page.hasPrevious()) put(box, s, 52, button(Items.ARROW, "Предыдущая", ""), GuiAction.number(GuiAction.Type.PAGE, -1));
        if (page.hasNext()) put(box, s, 53, button(Items.ARROW, "Следующая", ""), GuiAction.number(GuiAction.Type.PAGE, 1));
        openBox(player, s, box, "Мои заявки");
    }

    private void renderCancel(ServerPlayer player, MarketSession s) {
        SimpleContainer box = blank();
        s.resetActions();
        box.setItem(22, GuiItems.named(new ItemStack(Items.BARRIER), "Отменить заявку?",
                ChatFormatting.RED, "Остаток BUY будет освобождён из резерва.",
                "Остаток SELL попадёт в доставки.", "ID: " + s.pendingCancelId));
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
                    "Нажмите, чтобы забрать", "Доставка #" + delivery.deliveryId());
            put(box, s, CARD_SLOTS[i++], icon, GuiAction.delivery(delivery.deliveryId()));
        }
        put(box, s, 45, button(Items.ARROW, "На главную", "Вернуться к рынкам"), GuiAction.simple(GuiAction.Type.HOME));
        if (page.hasPrevious()) put(box, s, 52, button(Items.ARROW, "Предыдущая", ""), GuiAction.number(GuiAction.Type.PAGE, -1));
        if (page.hasNext()) put(box, s, 53, button(Items.ARROW, "Следующая", ""), GuiAction.number(GuiAction.Type.PAGE, 1));
        openBox(player, s, box, "Доставки");
    }

    private void claim(ServerPlayer player, MarketSession s, long deliveryId) {
        AuctionService.Outcome outcome = service().claimDelivery(player.getUUID(), deliveryId);
        showOutcome(player, outcome);
        renderDeliveries(player, s);
    }

    private void back(ServerPlayer player, MarketSession s) {
        if (s.screen == MarketScreen.CONFIRM_ORDER) {
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
            case HOME, SEARCH -> renderMarkets(player, s);
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
        s.transitioning = true;
        try {
            player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
                s.containerId = id;
                return new ServerChestMenu(id, inventory, box, this, s);
            }, Component.literal(title)));
        } finally {
            s.transitioning = false;
        }
    }

    private static SimpleContainer blank() {
        SimpleContainer box = new SimpleContainer(54);
        ItemStack filler = GuiItems.named(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), " ", ChatFormatting.DARK_GRAY);
        for (int i = 0; i < 54; i++) box.setItem(i, filler.copy());
        return box;
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
            String id = outcome.order() == null ? "" : " ID: " + outcome.order().orderId();
            tell(player, "Заявка принята и безопасно завершается." + id, ChatFormatting.YELLOW);
        } else if (outcome.isSuccess()) {
            tell(player, "Готово." + (outcome.order() == null ? "" : " ID: " + outcome.order().orderId()),
                    ChatFormatting.GREEN);
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

package com.valorcraft.vauction.application;

import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.market.MarketCard;
import com.valorcraft.vauction.domain.market.MarketSummary;
import com.valorcraft.vauction.domain.market.OrderBookLevel;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.item.ItemCodecException;
import com.valorcraft.vauction.item.ItemSnapshot;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.item.MarketKeyStrategy;
import com.valorcraft.vauction.item.SearchVocabulary;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.MarketReadRepository;
import com.valorcraft.vauction.persistence.OrderRepository;
import com.valorcraft.vauction.persistence.PlayerMarketActivityReadRepository;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Bounded, GUI-oriented reads. Mutations remain exclusively in {@link AuctionService}. */
public final class AuctionReadService {
    public static final int PAGE_SIZE = 45;
    public static final int BOOK_DEPTH = 7;
    private static final long RECENT_MARKET_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    public record MarketView(MarketCard card, List<OrderBookLevel> sells,
                             List<OrderBookLevel> buys) {
        public MarketView {
            sells = List.copyOf(sells);
            buys = List.copyOf(buys);
        }
    }

    public record QuoteLevel(long pricePerUnit, int quantity) {}

    /** Immutable, read-only preview. It never reserves funds or removes items. */
    public record ImmediateQuote(OrderSide side, int requestedQuantity, int fillableQuantity,
                                 List<QuoteLevel> levels, long expectedTotal,
                                 long worstExecutionPrice, BigDecimal averagePrice,
                                 boolean insufficientLiquidity) {
        public ImmediateQuote {
            levels = List.copyOf(levels);
        }

        public boolean executable() {
            return fillableQuantity > 0 && worstExecutionPrice > 0;
        }
    }

    private final DatabaseManager database;
    private final OrderRepository orders;
    private final DeliveryRepository deliveries;
    private final MarketReadRepository markets;
    private final PlayerMarketActivityReadRepository activity;
    private final ItemStackCodec codec;
    private final MarketKeyStrategy keys;
    private final boolean allowSelfPurchase;

    public AuctionReadService(DatabaseManager database, OrderRepository orders,
                              DeliveryRepository deliveries, ItemStackCodec codec,
                              MarketKeyStrategy keys) {
        this(database, orders, deliveries, codec, keys, false);
    }

    public AuctionReadService(DatabaseManager database, OrderRepository orders,
                              DeliveryRepository deliveries, ItemStackCodec codec,
                              MarketKeyStrategy keys, boolean allowSelfPurchase) {
        this.database = database;
        this.orders = orders;
        this.deliveries = deliveries;
        this.codec = codec;
        this.keys = keys;
        this.allowSelfPurchase = allowSelfPurchase;
        this.markets = new MarketReadRepository();
        this.activity = new PlayerMarketActivityReadRepository();
    }

    public Page<MarketCard> markets(int requestedPage, String query) {
        return markets(requestedPage, query, PAGE_SIZE);
    }

    public Page<MarketCard> markets(int requestedPage, String query, int requestedPageSize) {
        int pageSize = Math.max(1, Math.min(PAGE_SIZE, requestedPageSize));
        long cutoff = System.currentTimeMillis() - RECENT_MARKET_MILLIS;
        List<List<String>> groups = SearchVocabulary.groups(query);
        long total = database.query(c -> markets.count(c, groups, cutoff));
        int totalPages = Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                (total + pageSize - 1) / pageSize));
        int page = Math.min(Math.max(0, requestedPage), totalPages - 1);
        List<MarketCard> rows = database.query(c -> markets.page(c, groups, cutoff,
                page * pageSize, pageSize));
        return new Page<>(rows, page, page > 0, page + 1 < totalPages, total, totalPages);
    }

    public MarketView market(ItemStack selectedUnit) {
        if (selectedUnit == null || selectedUnit.isEmpty()) return null;
        ItemStack unit = selectedUnit.copy();
        unit.setCount(1);
        String key;
        ItemSnapshot fallback;
        try {
            key = keys.keyOf(unit);
            fallback = codec.encode(unit);
        } catch (ItemCodecException e) {
            return null;
        }
        return database.query(c -> {
            MarketCard card = markets.byKey(c, key).orElseGet(() -> new MarketCard(
                    new MarketSummary(key, fallback.displayName(), 0, 0, 0, 0, 0), fallback));
            return new MarketView(card,
                    orders.bookLevels(c, key, OrderSide.SELL, BOOK_DEPTH),
                    orders.bookLevels(c, key, OrderSide.BUY, BOOK_DEPTH));
        });
    }

    public ImmediateQuote quoteBuyNow(ItemStack unit, int quantity) {
        return quote(unit, quantity, OrderSide.BUY, null);
    }

    public ImmediateQuote quoteSellNow(ItemStack unit, int quantity) {
        return quote(unit, quantity, OrderSide.SELL, null);
    }

    public ImmediateQuote quoteBuyNow(ItemStack unit, int quantity, UUID actor) {
        return quote(unit, quantity, OrderSide.BUY, actor);
    }

    public ImmediateQuote quoteSellNow(ItemStack unit, int quantity, UUID actor) {
        return quote(unit, quantity, OrderSide.SELL, actor);
    }

    private ImmediateQuote quote(ItemStack selectedUnit, int requestedQuantity, OrderSide side, UUID actor) {
        int requested = Math.max(0, requestedQuantity);
        String key = marketKey(selectedUnit);
        if (key == null || requested == 0) {
            return new ImmediateQuote(side, requested, 0, List.of(), 0, 0,
                    BigDecimal.ZERO, requested > 0);
        }
        OrderSide liquiditySide = side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        List<OrderBookLevel> book = database.query(c ->
                orders.immediateLiquidity(c, key, liquiditySide,
                        allowSelfPurchase ? null : actor,
                        AuctionWorkLimits.MAX_IMMEDIATE_MATCH_FILLS));
        List<QuoteLevel> used = new ArrayList<>();
        int remaining = requested;
        long total = 0;
        long worst = 0;
        for (OrderBookLevel level : book) {
            if (remaining <= 0) break;
            int take = (int) Math.min((long) remaining, level.quantity());
            try {
                total = Math.addExact(total, Math.multiplyExact(level.pricePerUnit(), (long) take));
            } catch (ArithmeticException e) {
                return new ImmediateQuote(side, requested, 0, List.of(), 0, 0,
                        BigDecimal.ZERO, true);
            }
            if (!used.isEmpty() && used.get(used.size() - 1).pricePerUnit() == level.pricePerUnit()) {
                QuoteLevel previous = used.remove(used.size() - 1);
                used.add(new QuoteLevel(previous.pricePerUnit(), previous.quantity() + take));
            } else {
                used.add(new QuoteLevel(level.pricePerUnit(), take));
            }
            remaining -= take;
            worst = level.pricePerUnit();
        }
        int fillable = requested - remaining;
        BigDecimal average = fillable == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(total).divide(BigDecimal.valueOf(fillable), 2, RoundingMode.HALF_UP);
        return new ImmediateQuote(side, requested, fillable, used, total, worst, average,
                fillable < requested);
    }

    public Page<Order> playerOrders(UUID playerId, int requestedPage) {
        int page = Math.max(0, requestedPage);
        List<Order> rows = database.query(c -> orders.pageForOwner(c, playerId,
                page * PAGE_SIZE, PAGE_SIZE + 1));
        return trim(rows, page);
    }

    public Page<AuctionDelivery> deliveries(UUID playerId, int requestedPage) {
        int page = Math.max(0, requestedPage);
        List<AuctionDelivery> rows = database.query(c -> deliveries.claimablePage(c, playerId,
                page * PAGE_SIZE, PAGE_SIZE + 1));
        return trim(rows, page);
    }

    public Page<PlayerMarketActivity> playerActivity(UUID playerId, int requestedPage) {
        return playerActivity(playerId, requestedPage, PAGE_SIZE);
    }

    public Page<PlayerMarketActivity> playerActivity(UUID playerId, int requestedPage, int requestedPageSize) {
        int pageSize = Math.max(1, Math.min(PAGE_SIZE, requestedPageSize));
        int page = Math.max(0, requestedPage);
        List<PlayerMarketActivity> rows = database.query(c -> activity.page(c, playerId,
                page * pageSize, pageSize + 1));
        return trim(rows, page, pageSize);
    }

    public ItemStack visual(ItemSnapshot snapshot) {
        try {
            ItemStack result = codec.decode(snapshot);
            result.setCount(1);
            return result;
        } catch (ItemCodecException e) {
            return ItemStack.EMPTY;
        }
    }

    public String marketKey(ItemStack stack) {
        try {
            ItemStack unit = stack.copy();
            unit.setCount(1);
            return keys.keyOf(unit);
        } catch (RuntimeException | ItemCodecException e) {
            return null;
        }
    }

    private static <T> Page<T> trim(List<T> rows, int page) {
        return trim(rows, page, PAGE_SIZE);
    }

    private static <T> Page<T> trim(List<T> rows, int page, int pageSize) {
        boolean hasNext = rows.size() > pageSize;
        List<T> shown = hasNext ? new ArrayList<>(rows.subList(0, pageSize)) : rows;
        return new Page<>(shown, page, page > 0, hasNext);
    }
}

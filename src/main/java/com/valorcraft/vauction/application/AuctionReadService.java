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
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.MarketReadRepository;
import com.valorcraft.vauction.persistence.OrderRepository;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded, GUI-oriented reads. Mutations remain exclusively in {@link AuctionService}. */
public final class AuctionReadService {
    public static final int PAGE_SIZE = 28;
    public static final int BOOK_DEPTH = 7;
    private static final long RECENT_MARKET_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    public record MarketView(MarketCard card, List<OrderBookLevel> sells,
                             List<OrderBookLevel> buys) {
        public MarketView {
            sells = List.copyOf(sells);
            buys = List.copyOf(buys);
        }
    }

    private final DatabaseManager database;
    private final OrderRepository orders;
    private final DeliveryRepository deliveries;
    private final MarketReadRepository markets;
    private final ItemStackCodec codec;
    private final MarketKeyStrategy keys;

    public AuctionReadService(DatabaseManager database, OrderRepository orders,
                              DeliveryRepository deliveries, ItemStackCodec codec,
                              MarketKeyStrategy keys) {
        this.database = database;
        this.orders = orders;
        this.deliveries = deliveries;
        this.codec = codec;
        this.keys = keys;
        this.markets = new MarketReadRepository();
    }

    public Page<MarketCard> markets(int requestedPage, String query) {
        int page = Math.max(0, requestedPage);
        List<MarketCard> rows = database.query(c -> markets.page(c, query,
                System.currentTimeMillis() - RECENT_MARKET_MILLIS,
                page * PAGE_SIZE, PAGE_SIZE + 1));
        return trim(rows, page);
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
        boolean hasNext = rows.size() > PAGE_SIZE;
        List<T> shown = hasNext ? new ArrayList<>(rows.subList(0, PAGE_SIZE)) : rows;
        return new Page<>(shown, page, page > 0, hasNext);
    }
}

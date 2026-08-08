package com.valorcraft.vauction.application;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.domain.market.MarketSummary;
import com.valorcraft.vauction.domain.market.OrderBookLevel;
import com.valorcraft.vauction.domain.operation.AuctionOperation;
import com.valorcraft.vauction.domain.operation.OperationPhase;
import com.valorcraft.vauction.domain.operation.OperationType;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.trade.Trade;
import com.valorcraft.vauction.domain.trade.TradeState;
import com.valorcraft.vauction.economy.EconomyGateway;
import com.valorcraft.vauction.item.ItemCodecException;
import com.valorcraft.vauction.item.ItemPolicy;
import com.valorcraft.vauction.item.ItemSnapshot;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.item.MarketKeyStrategy;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DatabaseException;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.OperationRepository;
import com.valorcraft.vauction.persistence.OrderRepository;
import com.valorcraft.vauction.persistence.TradeRepository;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Бизнес-логика ЕДИНОГО order-book рынка VAuction (замена старого «listings +
 * buy orders» и мёртвого exchange-скелета).
 * <p>
 * Модель: {@code auction_orders} (BUY/SELL) + {@code auction_trades} (fill'ы) +
 * {@code auction_deliveries} (выдача предметов). Матчинг price-time:
 * <ul>
 *   <li>maker price = цена resting-ордера (сторона, стоявшая в книге);</li>
 *   <li>partial fills: каждый чанк — отдельный Trade с ДЕТЕРМИНИРОВАННЫМ uuid и
 *       собственной escrow-сагой; повтор попытки идемпотентен;</li>
 *   <li>self-trade пропускается; комиссия (bps, floor) — казне VEconomy;</li>
 *   <li>SELL-ордера удерживают предметы виртуально (остаток в БД); отмена/срок
 *       возвращают остаток delivery-письмом.</li>
 * </ul>
 * ВСЕ операции — с серверного потока; деньги — только через {@link EconomyGateway}.
 * Crash-границы S1..S4 восстановимы в {@code RecoveryService}: escrow-резерв без
 * Trade → release; Trade PENDING + escrow CAPTURED → SETTLED; Trade PENDING +
 * escrow RESERVED → провести settle (данных Trade достаточно).
 */
public final class AuctionService {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    private static final String REF_BUY = "vauction:buy:";
    private static final String ROLE_SELLER = "seller";
    private static final String ROLE_COMMISSION = "commission";
    private static final String PREFIX_TRADE_DELIVERY = "trade:";
    private static final String PREFIX_CANCEL_DELIVERY = "cancel:";
    private static final int BOOK_QUERY_LIMIT = 200;
    private static final long MILLIS_PER_DAY = 86_400_000L;

    public enum Result {
        SUCCESS, NOT_A_PLAYER, ORDER_NOT_FOUND, NOT_YOUR_ORDER, SELF_TRADE,
        INVALID_QUANTITY, INVALID_PRICE, OVER_LIMIT, BLACKLISTED,
        INSUFFICIENT_FUNDS, INSUFFICIENT_ITEMS, INVENTORY_FULL,
        ECONOMY_FAILED, DATABASE_FAILED, MARKET_DISABLED
    }

    /** Результат операции: статус + детали. */
    public record Outcome(Result status, String message, Order order, List<Trade> trades) {

        public static Outcome ok(String message, Order order, List<Trade> trades) {
            return new Outcome(Result.SUCCESS, message, order, trades);
        }

        public static Outcome fail(Result result, String message) {
            return new Outcome(result, message, null, List.of());
        }

        public boolean isSuccess() {
            return status == Result.SUCCESS;
        }

        /** Доля, исполненная мгновенным матчингом. */
        public long filledQuantity() {
            return trades.isEmpty() ? 0L : trades.stream().mapToLong(Trade::quantity).sum();
        }
    }

    private final DatabaseManager database;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final OperationRepository operations;
    private final DeliveryRepository deliveries;
    private final ItemStackCodec codec;
    private final MarketKeyStrategy marketKeyFactory;
    private final EconomyGateway economy;
    private final InventoryOps inventory;
    private final AuctionSettings settings;

    public AuctionService(DatabaseManager database, OrderRepository orders, TradeRepository trades,
                          OperationRepository operations, DeliveryRepository deliveries,
                          ItemStackCodec codec, MarketKeyStrategy marketKeyFactory,
                          EconomyGateway economy, InventoryOps inventory, AuctionSettings settings) {
        this.database = database;
        this.orders = orders;
        this.trades = trades;
        this.operations = operations;
        this.deliveries = deliveries;
        this.codec = codec;
        this.marketKeyFactory = marketKeyFactory;
        this.economy = economy;
        this.inventory = inventory;
        this.settings = settings;
    }

    // ================================================================ вход

    /**
     * Программный SELL-ордер: предметы {@code quantity} штук считаются уже
     * переданными рынку (в серверном потоке — {@link #createSellOrderFromSlot}).
     */
    public Outcome createSellOrder(UUID sellerId, ItemStack item, long pricePerUnit, int quantity) {
        if (sellerId == null || item == null || item.isEmpty()) {
            return Outcome.fail(Result.NOT_A_PLAYER, "Продавец не определён");
        }
        return placeSell(sellerId, item, pricePerUnit, quantity);
    }

    /** Выставить на продажу из слота инвентаря игрока (физическое списание). */
    public Outcome createSellOrderFromSlot(ServerPlayer seller, int slotIndex, long pricePerUnit, int quantity) {
        if (seller == null) {
            return Outcome.fail(Result.NOT_A_PLAYER, "Только для игроков");
        }
        if (slotIndex < 0) {
            return Outcome.fail(Result.INVALID_QUANTITY, "Некорректный слот");
        }
        ItemStack stack = seller.getInventory().getItem(slotIndex);
        if (stack.isEmpty() || stack.getCount() < quantity) {
            return Outcome.fail(Result.INSUFFICIENT_ITEMS, "В слоте недостаточно предметов");
        }
        if (!inventory.tryTake(seller.getUUID(), stack, quantity)) {
            return Outcome.fail(Result.INSUFFICIENT_ITEMS, "Не удалось списать предметы");
        }
        ItemStack sellUnit = stack.copy();
        sellUnit.setCount(quantity);
        Outcome outcome = createSellOrder(seller.getUUID(), sellUnit, pricePerUnit, quantity);
        if (!outcome.isSuccess()) {
            inventory.give(seller.getUUID(), sellUnit);
        }
        return outcome;
    }

    public Outcome createBuyOrder(UUID buyerId, ItemStack unit, long pricePerUnit, int quantity) {
        if (buyerId == null) {
            return Outcome.fail(Result.NOT_A_PLAYER, "Покупатель не определён");
        }
        if (unit == null || unit.isEmpty()) {
            return Outcome.fail(Result.INVALID_QUANTITY, "Предмет не определён");
        }
        if (quantity <= 0) {
            return Outcome.fail(Result.INVALID_QUANTITY, "Количество должно быть положительным");
        }
        if (pricePerUnit <= 0) {
            return Outcome.fail(Result.INVALID_PRICE, "Цена должна быть положительной");
        }
        if (!settings.enabled()) {
            return Outcome.fail(Result.MARKET_DISABLED, "Рынок отключён");
        }
        var policy = ItemPolicy.check(unit, settings);
        if (!policy.allowed()) {
            return Outcome.fail(Result.BLACKLISTED,
                    policy.detail() == null ? "Предмет запрещён политикой" : policy.detail());
        }
        long total;
        try {
            total = Math.multiplyExact(pricePerUnit, quantity);
        } catch (ArithmeticException e) {
            return Outcome.fail(Result.INVALID_PRICE, "Сумма выходит за пределы");
        }
        if (total <= 0) {
            return Outcome.fail(Result.INVALID_PRICE, "Сумма должна быть положительной");
        }
        if (!economy.isAvailable()) {
            return Outcome.fail(Result.ECONOMY_FAILED, "Экономика недоступна");
        }
        if (!economy.has(buyerId, total)) {
            return Outcome.fail(Result.INSUFFICIENT_FUNDS, "Недостаточно средств");
        }
        ItemSnapshot unitSnapshot;
        try {
            unitSnapshot = canonicalUnit(unit);
        } catch (ItemCodecException e) {
            return Outcome.fail(Result.BLACKLISTED, "Предмет не удалось закодировать");
        }
        return placeBuy(buyerId, unit, unitSnapshot, pricePerUnit, quantity);
    }

    // ================================================================ размещение

    private Outcome placeBuy(UUID buyerId, ItemStack unit, ItemSnapshot unitSnapshot,
                             long pricePerUnit, int quantity) {
        String key = marketKeyOf(unit);
        if (key == null) {
            return Outcome.fail(Result.BLACKLISTED, "Предмет не допустим на рынке");
        }
        if (settings.maxBuyOrdersPerPlayer() > 0
                && countActiveOrders(buyerId, OrderSide.BUY) >= settings.maxBuyOrdersPerPlayer()) {
            return Outcome.fail(Result.OVER_LIMIT, "Достигнут лимит активных buy-заявок");
        }
        long now = now();
        UUID orderId = UUID.randomUUID();
        Order order;
        try {
            Order created = Order.newOrder(buyerId, OrderSide.BUY, key, unitSnapshot,
                    pricePerUnit, quantity, now).orderId(orderId).build();
            order = created;
            database.inTransaction(conn -> {
                orders.insert(conn, created);
                operations.insert(conn, operationEntry(OperationType.CREATE_BUY_ORDER,
                                "op:create-buy:" + orderId, orderId, buyerId, OperationPhase.BEGIN, now)
                        .operationId("create-buy-" + orderId).build());
                return null;
            });
        } catch (RuntimeException e) {
            return Outcome.fail(Result.DATABASE_FAILED, e.getMessage());
        }

        List<Order> resting = database.query(conn ->
                orders.bestSells(conn, key, pricePerUnit, BOOK_QUERY_LIMIT));
        List<Trade> fills = new ArrayList<>();
        int need = quantity;
        int seq = 0;
        for (Order sellOrder : resting) {
            if (need <= 0) {
                break;
            }
            if (sellOrder.ownerUuid().equals(buyerId) && !settings.allowSelfPurchase()) {
                continue;
            }
            int chunk = Math.min(need, sellOrder.remainingQuantity());
            Trade fill = executeFill(buyerId, sellOrder.ownerUuid(), order, sellOrder, chunk,
                    sellOrder.pricePerUnit(), OrderSide.SELL, seq, now);
            if (fill == null) {
                break;
            }
            fills.add(fill);
            need -= chunk;
            seq++;
        }
        completeOp("create-buy-" + orderId);
        return new Outcome(Result.SUCCESS,
                fills.isEmpty() ? "Заявка размещена: " + orderId : "Заявка исполнена: " + orderId,
                order, fills);
    }

    private Outcome placeSell(UUID sellerId, ItemStack unit, long pricePerUnit, int quantity) {
        if (quantity <= 0) {
            return Outcome.fail(Result.INVALID_QUANTITY, "Количество должно быть положительным");
        }
        if (pricePerUnit <= 0) {
            return Outcome.fail(Result.INVALID_PRICE, "Цена должна быть положительной");
        }
        if (!settings.enabled()) {
            return Outcome.fail(Result.MARKET_DISABLED, "Рынок отключён");
        }
        var policy = ItemPolicy.check(unit, settings);
        if (!policy.allowed()) {
            return Outcome.fail(Result.BLACKLISTED,
                    policy.detail() == null ? "Предмет запрещён политикой" : policy.detail());
        }
        ItemSnapshot unitSnapshot;
        String key;
        try {
            unitSnapshot = canonicalUnit(unit);
            key = marketKeyFactory.keyOf(unit);
        } catch (ItemCodecException e) {
            return Outcome.fail(Result.BLACKLISTED, "Предмет не удалось закодировать");
        }
        if (settings.maxActiveListingsPerPlayer() > 0
                && countActiveOrders(sellerId, OrderSide.SELL) >= settings.maxActiveListingsPerPlayer()) {
            return Outcome.fail(Result.OVER_LIMIT, "Достигнут лимит активных sell-ордеров");
        }
        long now = now();
        UUID orderId = UUID.randomUUID();
        Order order;
        try {
            Order created = Order.newOrder(sellerId, OrderSide.SELL, key, unitSnapshot,
                    pricePerUnit, quantity, now).orderId(orderId).build();
            order = created;
            database.inTransaction(conn -> {
                orders.insert(conn, created);
                operations.insert(conn, operationEntry(OperationType.CREATE_SELL_ORDER,
                                "op:create-sell:" + orderId, orderId, sellerId,
                                OperationPhase.BEGIN, now)
                        .operationId("create-sell-" + orderId).build());
                return null;
            });
        } catch (RuntimeException e) {
            return Outcome.fail(Result.DATABASE_FAILED, e.getMessage());
        }

        List<Order> resting = database.query(conn ->
                orders.bestBuys(conn, key, pricePerUnit, BOOK_QUERY_LIMIT));
        List<Trade> fills = new ArrayList<>();
        int need = quantity;
        int seq = 0;
        for (Order buyOrder : resting) {
            if (need <= 0) {
                break;
            }
            if (buyOrder.ownerUuid().equals(sellerId) && !settings.allowSelfPurchase()) {
                continue;
            }
            int chunk = Math.min(need, buyOrder.remainingQuantity());
            Trade fill = executeFill(buyOrder.ownerUuid(), sellerId, buyOrder, order, chunk,
                    buyOrder.pricePerUnit(), OrderSide.BUY, seq, now);
            if (fill == null) {
                break;
            }
            fills.add(fill);
            need -= chunk;
            seq++;
        }
        completeOp("create-sell-" + orderId);
        return new Outcome(Result.SUCCESS, "Ордер размещён: " + orderId, order, fills);
    }

    // ================================================================ fill-сага

    /**
     * Один чанк исполнения. S1 reserve → S2 intent(БД) → S3 settle →
     * S4 фиксация. Возвращает Trade или {@code null} при сбое.
     */
    private Trade executeFill(UUID buyerId, UUID sellerId, Order buyOrder, Order sellOrder,
                              int chunk, long makerPrice, OrderSide makerSide, int seq, long now) {
        long gross = Math.multiplyExact(makerPrice, chunk);
        long commission = (gross * (long) settings.commissionBps()) / 10_000L;
        long sellerNet = gross - commission;
        String ref = REF_BUY + buyOrder.orderId() + ":" + seq;
        UUID tradeId = deterministicTradeId(buyOrder.orderId(), sellOrder.orderId(), seq);

        EconomyGateway.ReserveResult reserve = economy.reserve(buyerId, gross, ref,
                "fill escrow " + tradeId, "va:reserve:" + tradeId);
        if (!reserve.isSuccessOrIdempotent()) {
            LOGGER.warn("S1 reserve failed ref={} status={}", ref, reserve.status());
            if (reserve.status() == EconomyGateway.ReserveStatus.INSUFFICIENT_FUNDS) {
                markManualReview(buyOrder, tradeId, "недостаточно средств на fill");
            }
            return null;
        }

        Trade pending = Trade.newTrade(buyOrder.marketKey(), buyOrder.orderId(), sellOrder.orderId(),
                        makerSide, makerPrice, chunk, commission, buyerId, sellerId, ref, now)
                .tradeId(tradeId).build();
        try {
            database.inTransaction(conn -> {
                recordIntent(conn, pending, buyOrder, sellOrder, chunk, now);
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("S2 intent failed {}: {}", tradeId, e.getMessage());
            releaseWithCompensation(pending, buyOrder, sellOrder, chunk, ref);
            return null;
        }

        EconomyGateway.SettleResult settled = economy.settle(ref,
                List.of(new EconomyGateway.Credit(sellerId, sellerNet, ROLE_SELLER),
                        new EconomyGateway.Credit(economy.treasury(), commission, ROLE_COMMISSION)),
                "settle fill " + tradeId, "va:settle:" + tradeId);
        if (!settled.isSuccessOrIdempotent()) {
            releaseWithCompensation(pending, buyOrder, sellOrder, chunk, ref);
            return null;
        }

        try {
            database.inTransaction(conn -> {
                trades.markSettled(conn, pending, now());
                completeOp(conn, "fill-" + tradeId);
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("S4 finalize failed (восстановимо recovery'ем): {}", e.getMessage());
        }
        return pending;
    }

    /** S2-интенты (trade + delivery + consumption) одним tx; повтор безопасен. */
    private void recordIntent(Connection conn, Trade pending, Order buyOrder, Order sellOrder,
                              int chunk, long now) {
        if (trades.findById(conn, pending.tradeId()).isPresent()) {
            return;
        }
        trades.insert(conn, pending);
        deliveries.insert(conn, AuctionDelivery.newDelivery(
                        pending.buyerUuid(), 0L, "fill-" + pending.tradeId(),
                        DeliveryType.PURCHASED, withQuantity(sellOrder.item(), chunk), now)
                .dedupeKey(PREFIX_TRADE_DELIVERY + pending.tradeId())
                .build());
        operations.insert(conn, operationEntry(OperationType.EXECUTE_FILL,
                        "op:fill:" + pending.tradeId(), pending.buyOrderId(), pending.sellerUuid(),
                        OperationPhase.ESCROW_SETTLE, now)
                .operationId("fill-" + pending.tradeId()).build());
        if (orders.tryConsume(conn, sellOrder, chunk, now) == null) {
            throw new DatabaseException("sell consume conflict " + sellOrder.orderId());
        }
        if (orders.tryConsume(conn, buyOrder, chunk, now) == null) {
            throw new DatabaseException("buy consume conflict " + buyOrder.orderId());
        }
    }

    /** Откат при провале S3: деньги возвращаются release, письмо и потребления — компенсируются. */
    private void releaseWithCompensation(Trade pending, Order buyOrder, Order sellOrder, int chunk, String ref) {
        economy.release(ref, "compensate fill " + pending.tradeId(), "va:release:" + pending.tradeId());
        int c = chunk;
        long now = now();
        try {
            database.inTransaction(conn -> {
                trades.findById(conn, pending.tradeId())
                        .filter(t -> t.state() == TradeState.PENDING)
                        .ifPresent(t -> trades.markFailed(conn, t));
                deliveries.findByDedupeKey(conn, PREFIX_TRADE_DELIVERY + pending.tradeId())
                        .ifPresent(d -> deliveries.applyState(conn, d, d.toFailed("settle failed")));
                restoreConsumption(conn, buyOrder, sellOrder, c, now);
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.error("compensation failed {}: {}", pending.tradeId(), e.getMessage());
        }
    }

    private void restoreConsumption(Connection conn, Order buyOrder, Order sellOrder, int chunk, long now) {
        Order buyNow = orders.findById(conn, buyOrder.orderId()).orElse(null);
        if (buyNow != null && buyNow.filledQuantity() > 0) {
            orders.applyState(conn, buyNow, buyNow.restore(chunk, now));
        }
        Order sellNow = orders.findById(conn, sellOrder.orderId()).orElse(null);
        if (sellNow != null && sellNow.filledQuantity() > 0) {
            orders.applyState(conn, sellNow, sellNow.restore(chunk, now));
        }
    }

    // ================================================================ отмена

    public Outcome cancel(UUID actorId, UUID orderId, String reason) {
        long now = now();
        try {
            return database.inTransaction(conn -> {
                Optional<Order> found = orders.findById(conn, orderId);
                if (found.isEmpty()) {
                    return Outcome.fail(Result.ORDER_NOT_FOUND, "Ордер не найден");
                }
                Order order = found.get();
                if (!order.ownerUuid().equals(actorId)) {
                    return Outcome.fail(Result.NOT_YOUR_ORDER, "Не ваш ордер");
                }
                if (!order.isActive()) {
                    return Outcome.fail(Result.ORDER_NOT_FOUND, "Ордер уже завершён");
                }
                Order cancelled = order.cancelled(now);
                if (!orders.applyState(conn, order, cancelled)) {
                    return Outcome.fail(Result.DATABASE_FAILED, "Конфликт при отмене");
                }
                operations.insert(conn, operationEntry(OperationType.CANCEL_ORDER,
                                "op:cancel:" + orderId, orderId, actorId, OperationPhase.COMPLETE, now)
                        .operationId("cancel-order-" + orderId).build());
                if (order.side() == OrderSide.SELL && order.remainingQuantity() > 0) {
                    deliveries.insert(conn, AuctionDelivery.newDelivery(
                                    actorId, 0L, "cancel-order-" + orderId,
                                    DeliveryType.CANCELLED_RETURN,
                                    withQuantity(order.item(), order.remainingQuantity()), now)
                            .dedupeKey(PREFIX_CANCEL_DELIVERY + orderId)
                            .build());
                }
                completeOp(conn, "cancel-order-" + orderId);
                return Outcome.ok("Ордер отменён", cancelled, List.of());
            });
        } catch (RuntimeException e) {
            return Outcome.fail(Result.DATABASE_FAILED, e.getMessage());
        }
    }

    // ================================================================ claim

    public Outcome claimDelivery(UUID actorId, long deliveryId) {
        long now = now();
        AuctionDelivery found = database.query(c -> deliveries.findById(c, deliveryId).orElse(null));
        if (found == null) {
            return Outcome.fail(Result.ORDER_NOT_FOUND, "Письмо не найдено");
        }
        if (!found.playerUuid().equals(actorId)) {
            return Outcome.fail(Result.NOT_YOUR_ORDER, "Чужое письмо");
        }
        if (found.state() != DeliveryState.PENDING && found.state() != DeliveryState.CLAIMABLE) {
            return Outcome.fail(Result.ORDER_NOT_FOUND, "Письмо уже выдано");
        }
        AuctionDelivery ready = found.state() == DeliveryState.PENDING
                ? found.toClaimable(now, "claim-" + deliveryId)
                : found;
        AuctionDelivery claiming = ready.toClaiming(now);
        boolean locked = database.inTransaction(c -> deliveries.applyState(c, found, claiming));
        if (!locked) {
            return Outcome.fail(Result.DATABASE_FAILED, "Письмо уже забирается другим вызовом");
        }
        ItemStack stack;
        try {
            stack = codec.decode(ready.item());
        } catch (ItemCodecException e) {
            LOGGER.warn("claim {} decode failed: {}", deliveryId, e.getMessage());
            database.inTransaction(c -> deliveries.applyState(c, claiming, claiming.toFailed("decode failed")));
            return Outcome.fail(Result.DATABASE_FAILED, "Не удалось восстановить предмет");
        }
        if (stack.isEmpty()) {
            database.inTransaction(c -> deliveries.applyState(c, claiming, claiming.toFailed("decode failed")));
            return Outcome.fail(Result.DATABASE_FAILED, "Не удалось восстановить предмет");
        }
        ItemStack leftover = inventory.give(actorId, stack);
        if (!leftover.isEmpty()) {
            database.inTransaction(c -> deliveries.applyState(c, claiming,
                    claiming.toPending("inventory full")));
            return Outcome.fail(Result.INVENTORY_FULL, "Инвентарь полон");
        }
        boolean completed = database.inTransaction(c ->
                deliveries.applyState(c, claiming, claiming.toClaimed(now)));
        return completed ? Outcome.ok("Письмо получено", null, List.of())
                : Outcome.fail(Result.DATABASE_FAILED, "Не удалось завершить получение");
    }

    // ================================================================ expiry

    public int expirePass(long now) {
        int processed = 0;
        long sellCutoff = now - (long) settings.sellOrderExpiryDays() * MILLIS_PER_DAY;
        List<Order> sells = database.query(c ->
                orders.oldestActive(c, OrderSide.SELL, sellCutoff, BOOK_QUERY_LIMIT));
        for (Order o : sells) {
            try {
                database.inTransaction(c -> expireSingle(c, o, now));
                processed++;
            } catch (RuntimeException e) {
                LOGGER.warn("expire SELL {} failed: {}", o.orderId(), e.getMessage());
            }
        }
        long buyCutoff = now - (long) settings.buyOrderExpiryDays() * MILLIS_PER_DAY;
        List<Order> buys = database.query(c ->
                orders.oldestActive(c, OrderSide.BUY, buyCutoff, BOOK_QUERY_LIMIT));
        for (Order o : buys) {
            try {
                database.inTransaction(c -> expireSingle(c, o, now));
                processed++;
            } catch (RuntimeException e) {
                LOGGER.warn("expire BUY {} failed: {}", o.orderId(), e.getMessage());
            }
        }
        return processed;
    }

    private boolean expireSingle(Connection conn, Order order, long now) {
        Order expired = order.expired(now);
        if (!orders.applyState(conn, order, expired)) {
            return false;
        }
        operations.insert(conn, operationEntry(OperationType.EXPIRE,
                        "op:expire:" + order.orderId(), order.orderId(), order.ownerUuid(),
                        OperationPhase.COMPLETE, now)
                .operationId("expire-" + order.orderId()).build());
        if (order.side() == OrderSide.SELL && order.remainingQuantity() > 0) {
            deliveries.insert(conn, AuctionDelivery.newDelivery(
                            order.ownerUuid(), 0L, "expire-order-" + order.orderId(),
                            DeliveryType.CANCELLED_RETURN,
                            withQuantity(order.item(), order.remainingQuantity()), now)
                    .dedupeKey("expire:" + order.orderId())
                    .build());
        }
        completeOp(conn, "expire-" + order.orderId());
        return true;
    }

    // ================================================================ сводки

    public MarketSummary summary(ItemStack unit) {
        String key = marketKeyOf(unit);
        if (key == null) {
            return null;
        }
        return database.query(conn -> {
            ItemSnapshot visual = restVisual(conn, key);
            return new MarketSummary(key,
                    visual == null ? "?" : visual.displayName(),
                    orders.bestPrice(conn, key, OrderSide.BUY),
                    orders.bestPrice(conn, key, OrderSide.SELL),
                    orders.totalRemaining(conn, key, OrderSide.BUY),
                    orders.totalRemaining(conn, key, OrderSide.SELL),
                    trades.lastTradePrice(conn, key));
        });
    }

    private ItemSnapshot restVisual(Connection conn, String key) {
        List<Order> buys = orders.bestBuys(conn, key, 1L, 1);
        if (!buys.isEmpty()) {
            return buys.get(0).item();
        }
        List<Order> sells = orders.bestSells(conn, key, Long.MAX_VALUE, 1);
        return sells.isEmpty() ? null : sells.get(0).item();
    }

    public List<OrderBookLevel> bookLevels(ItemStack unit, OrderSide side) {
        String key = marketKeyOf(unit);
        if (key == null) {
            return List.of();
        }
        return database.query(conn -> orders.bookLevels(conn, key, side));
    }

    public List<Order> playerOrders(UUID ownerId) {
        return database.query(conn -> orders.activeForOwner(conn, ownerId));
    }

    public long lastTradePrice(ItemStack unit) {
        String key = marketKeyOf(unit);
        if (key == null) {
            return 0L;
        }
        return database.query(conn -> trades.lastTradePrice(conn, key));
    }

    // ================================================================ helpers

    private int countActiveOrders(UUID owner, OrderSide side) {
        return database.query(conn -> {
            int n = 0;
            for (Order o : orders.activeForOwner(conn, owner)) {
                if (o.side() == side) {
                    n++;
                }
            }
            return n;
        });
    }

    private AuctionOperation.Builder operationEntry(OperationType type, String idempotencyKey,
                                                    UUID target, UUID actor, OperationPhase phase, long now) {
        return AuctionOperation.newOperation(type, idempotencyKey, now)
                .phase(phase).actor(actor).target(target);
    }

    private void completeOp(String operationId) {
        try {
            database.inTransaction(conn -> completeOp(conn, operationId));
        } catch (RuntimeException ignored) {
            // журнал операций опционален
        }
    }

    private boolean completeOp(Connection conn, String operationId) {
        return operations.findById(conn, operationId)
                .map(op -> operations.applyRetry(conn, op.operationId(), op.attemptCount(),
                        op.toCompleted(now())))
                .orElse(false);
    }

    private void markManualReview(Order order, UUID tradeId, String reason) {
        try {
            database.inTransaction(c -> {
                Order fresh = orders.findById(c, order.orderId()).orElse(order);
                if (fresh.isActive()) {
                    orders.applyState(c, fresh, fresh.toManualReview(now()));
                }
                operations.findById(c, "fill-" + tradeId)
                        .ifPresent(op -> operations.applyRetry(c, op.operationId(), op.attemptCount(),
                                op.toManualReview(reason, now())));
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("MANUAL_REVIEW mark failed: {}", e.getMessage());
        }
    }

    private static UUID deterministicTradeId(UUID buyOrderId, UUID sellOrderId, int seq) {
        return UUID.nameUUIDFromBytes(
                (buyOrderId + ":" + sellOrderId + ":" + seq).getBytes(StandardCharsets.UTF_8));
    }

    private String marketKeyOf(ItemStack unit) {
        try {
            return marketKeyFactory.keyOf(unit);
        } catch (ItemCodecException e) {
            return null;
        }
    }

    private ItemSnapshot canonicalUnit(ItemStack unit) throws ItemCodecException {
        ItemStack copy = unit.copy();
        copy.setCount(1);
        return codec.encode(copy);
    }

    private static ItemSnapshot withQuantity(ItemSnapshot unit, int quantity) {
        return new ItemSnapshot(unit.serializedData(), unit.codecVersion(), unit.hash(),
                unit.registryId(), unit.displayName(), unit.searchName(), quantity);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
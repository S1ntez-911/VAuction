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
 * Бизнес-логика ЕДИНОГО order-book рынка VAuction.
 * <p>
 * Модель денег: **BUY-заявка полностью обеспечена escrow при создании**
 * (резервируется {@code quantity * limitPrice}; ссылка {@code vauction:buy:<id>:<эпоха>}).
 * Каждый fill — «settle старой эпохи → при необходимости новый резерв остатка
 * (следующая эпоха)»; остаток никогда не торгуется без покрытия, ссылки
 * никогда не переиспользуются. SELL-заявка удерживает предметы в БД.
 * <p>
 * Шаги одного fill (все повторимы, идемпотентны через {@code tradeId} и ref):
 * <ul>
 *   <li>S2 intent: trade(PENDING) + журнал + CAS-потребление обеих сторон
 *       (при любом сбое БД — откат целиком, КОМПЕНСАЦИЯ НЕ ТРЕБУЕТСЯ);</li>
 *   <li>S3 settle старой эпохи: продавец(net) + казна(комиссия) + покупатель(refund);</li>
 *   <li>S4 relock новой эпохи (если остаток) и финальная фиксация:
 *       trade→SETTLED, delivery→CLAIMABLE, {@code escrowReference/refEpoch} ордера.</li>
 * </ul>
 * Каждый пункт можно безопасно повторить после краха ({@link RecoveryService}).
 * <p>
 * Полная модель: все операции — с серверного потока; оптимистичные блокировки
 * (CAS по version) спасают от двойного fill; partial fills несколькими
 * продавцами перечитывают buy-ордер после каждого chunk (проблема устаревшей
 * version и повторной эпохи исключена).
 */
public final class AuctionService {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    private static final String REF_BUY = "vauction:buy:";
    private static final String ROLE_SELLER = "seller";
    private static final String ROLE_COMMISSION = "commission";
    private static final String ROLE_BUYER_REFUND = "buyer-refund";
    /** Верхний предел одного delivery; фактический предел учитывает max stack size предмета. */
    private static final int MAX_DELIVERY_CHUNK = 64;
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

    /** Выставить на продажу из слота инвентаря (сбор одинаковых стеков из нескольких слотов). */
    public Outcome createSellOrderFromSlot(ServerPlayer seller, int slotIndex, long pricePerUnit, int quantity) {
        if (seller == null) {
            return Outcome.fail(Result.NOT_A_PLAYER, "Только для игроков");
        }
        if (slotIndex < 0 || slotIndex >= seller.getInventory().getContainerSize()) {
            return Outcome.fail(Result.INVALID_QUANTITY, "Некорректный слот");
        }
        ItemStack stack = seller.getInventory().getItem(slotIndex);
        if (stack.isEmpty()) {
            return Outcome.fail(Result.INSUFFICIENT_ITEMS, "Пустой слот");
        }
        if (quantity <= 0) {
            return Outcome.fail(Result.INVALID_QUANTITY, "Количество должно быть положительным");
        }
        // #11: пред-роверка по ВСЕМУ инвентарю (не только выбранный слот)
        ItemStack probe = stack.copy();
        probe.setCount(1);
        int available = inventory.availableCount(seller.getUUID(), probe);
        if (quantity > available) {
            return Outcome.fail(Result.INSUFFICIENT_ITEMS,
                    "Недостаточно предметов в инвентаре (доступно " + available + ")");
        }
        if (!inventory.tryTake(seller.getUUID(), probe, quantity)) {
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
        return placeBuy(buyerId, unit, unitSnapshot, pricePerUnit, quantity, total);
    }

    // ================================================================ размещение

    private Outcome placeBuy(UUID buyerId, ItemStack unit, ItemSnapshot unitSnapshot,
                             long pricePerUnit, int quantity, long total) {
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
        String ref0 = refFor(orderId, 0);
        Order order;
        try {
            Order created = Order.newOrder(buyerId, OrderSide.BUY, key, unitSnapshot,
                            pricePerUnit, quantity, now)
                    .orderId(orderId)
                    .refEpoch(0)
                    .escrowReference(ref0)
                    .build();
            order = created;
            database.inTransaction(conn -> {
                orders.insert(conn, created);
                operations.insert(conn, operationEntry(OperationType.CREATE_BUY_ORDER,
                                "op:create-buy:" + orderId, orderId, buyerId,
                                OperationPhase.ESCROW_RESERVE, now)
                        .operationId("create-buy-" + orderId).build());
                return null;
            });
        } catch (RuntimeException e) {
            return Outcome.fail(Result.DATABASE_FAILED, e.getMessage());
        }

        // Durable intent уже содержит deterministic ref: crash до/после reserve
        // восстанавливается сканированием активных BUY, orphan escrow не возникает.
        EconomyGateway.ReserveResult r0 = economy.reserve(buyerId, total, ref0,
                "buy hold " + orderId, "va:buy:" + orderId);
        if (!r0.isSuccessOrIdempotent()) {
            LOGGER.warn("reserve on buy creation failed {}: {}", ref0, r0.status());
            markCreateBuyFailed(orderId, r0.status().name());
            if (r0.status() == EconomyGateway.ReserveStatus.INSUFFICIENT_FUNDS) {
                return Outcome.fail(Result.INSUFFICIENT_FUNDS, "Недостаточно средств");
            }
            return Outcome.fail(Result.ECONOMY_FAILED, "Не удалось зарезервировать средства");
        }

        List<Order> resting = database.query(conn ->
                orders.bestSells(conn, key, pricePerUnit, BOOK_QUERY_LIMIT));
        List<Trade> fills = new ArrayList<>();
        int need = quantity;
        Order cur = order;
        for (Order sellOrder : resting) {
            if (need <= 0 || cur == null || !cur.isActive()) {
                break;
            }
            if (sellOrder.ownerUuid().equals(buyerId) && !settings.allowSelfPurchase()) {
                continue;
            }
            int chunk = Math.min(need, sellOrder.remainingQuantity());
            if (chunk <= 0) {
                continue;
            }
            Trade fill = executeFill(cur, sellOrder, chunk, sellOrder.pricePerUnit(),
                    OrderSide.SELL, now);
            if (fill == null) {
                break;
            }
            fills.add(fill);
            need -= chunk;
            // #3: свежий объект buy-ордера — версия и эпоха escrow устаревают
            UUID curOrderId = cur.orderId();
            cur = database.query(c -> orders.findById(c, curOrderId).orElse(null));
        }
        completeOp("create-buy-" + orderId);
        Order finalOrder = cur == null ? order : cur;
        return new Outcome(Result.SUCCESS,
                fills.isEmpty() ? "Заявка размещена: " + orderId : "Заявка исполнена: " + orderId,
                finalOrder, fills);
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
                            pricePerUnit, quantity, now)
                    .orderId(orderId).build();
            order = created;
            database.inTransaction(conn -> {
                orders.insert(conn, created);
                operations.insert(conn, operationEntry(OperationType.CREATE_SELL_ORDER,
                                "op:create-sell:" + orderId, orderId, sellerId,
                                OperationPhase.COMPLETE, now)
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
        Order cur = order;
        for (Order buyOrder : resting) {
            if (need <= 0 || cur == null || !cur.isActive()) {
                break;
            }
            if (buyOrder.ownerUuid().equals(sellerId) && !settings.allowSelfPurchase()) {
                continue;
            }
            int chunk = Math.min(need, buyOrder.remainingQuantity());
            if (chunk <= 0) {
                continue;
            }
            Trade fill = executeFill(buyOrder, cur, chunk, buyOrder.pricePerUnit(),
                    OrderSide.BUY, now);
            if (fill == null) {
                break;
            }
            fills.add(fill);
            need -= chunk;
            // #3 свежий объект основной стороны (taker)
            UUID curOrderId = cur.orderId();
            cur = database.query(c -> orders.findById(c, curOrderId).orElse(null));
        }
        completeOp("create-sell-" + orderId);
        Order finalOrder = cur != null ? cur : order;
        return new Outcome(Result.SUCCESS, "Ордер размещён: " + orderId, finalOrder, fills);
    }

    // ================================================================ fill-сага

    /**
     * Один chunk. S2 (intent, tx) → S3 (settlement) → S4 (relock + finalize).
     * Возвращает Trade или {@code null} при сбое одного из шагов
     * (повтор этого же fill — идемпотентен через tradeId и escrow-ref).
     */
    private Trade executeFill(Order buyOrder, Order sellOrder, int chunk,
                              long makerPrice, OrderSide makerSide, long now) {
        if (buyOrder.escrowReference() == null || buyOrder.escrowReference().isBlank()) {
            LOGGER.error("fill {}: buy order без escrow reference", buyOrder.orderId());
            return null;
        }
        String ref = buyOrder.escrowReference();
        long commission = commissionOf(makerPrice, chunk);
        UUID tradeId = deterministicTradeId(buyOrder.orderId(), sellOrder.orderId(),
                buyOrder.refEpoch());
        Trade pending = Trade.newTrade(buyOrder.marketKey(), buyOrder.orderId(), sellOrder.orderId(),
                        makerSide, makerPrice, chunk, commission, buyOrder.ownerUuid(),
                        sellOrder.ownerUuid(), ref, now)
                .tradeId(tradeId).build();

        // S2: intent одним tx; любой сбой откатывает ВСЁ — компенсация не нужна (#3)
        try {
            database.inTransaction(conn -> {
                if (trades.findById(conn, tradeId).isPresent()) {
                    return null; // повтор (recovery) — сага не задублируется
                }
                trades.insert(conn, pending);
                operations.insert(conn, operationEntry(OperationType.EXECUTE_FILL,
                                "op:fill:" + tradeId, buyOrder.orderId(), sellOrder.ownerUuid(),
                                OperationPhase.ESCROW_SETTLE, now)
                        .operationId("fill-" + tradeId).build());
                if (orders.tryConsume(conn, sellOrder, chunk, now) == null) {
                    throw new DatabaseException("sell consume conflict " + sellOrder.orderId());
                }
                if (orders.tryConsume(conn, buyOrder, chunk, now) == null) {
                    throw new DatabaseException("buy consume conflict " + buyOrder.orderId());
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("S2 intent failed {}: {}", tradeId, e.getMessage());
            return null;
        }
        int remainingAfter = buyOrder.remainingQuantity() - chunk;
        if (!settleAfterIntent(pending, buyOrder, sellOrder, chunk, remainingAfter, now)) {
            return null;
        }
        return database.query(c -> trades.findById(c, tradeId).orElse(pending));
    }

    /**
     * Публичный idempotent «допроведение» fill после краха в фазах S3/S4.
     * Используется {@link RecoveryService}; параметры полностью выводятся из
     * данных Trade и свежего ордера.
     */
    public boolean resumeFill(Trade trade) {
        if (trade.state() != TradeState.PENDING) {
            return true;
        }
        Order buy = database.query(c -> orders.findById(c, trade.buyOrderId()).orElse(null));
        if (buy == null) {
            LOGGER.error("resumeFill {}: buy order не найден", trade.tradeId());
            return false;
        }
        Order sellOrder = database.query(c -> orders.findById(c, trade.sellOrderId()).orElse(null));
        if (sellOrder == null) {
            LOGGER.error("resumeFill {}: sell order не найден", trade.tradeId());
            return false;
        }
        int remainingAfter = Math.max(0, buy.remainingQuantity()); // consumption уже применено
        return settleAfterIntent(trade, buy, sellOrder, trade.quantity(), remainingAfter, now());
    }

    /**
     * S3 + S4. Только деньги и финальная фиксация; идемпотентность —
     * через escrow-ref и CAS по trade/delivery/order. {@code remainingAfter} —
     * остаток buy-ордера ПОСЛЕ применения этого fill.
     */
    private boolean settleAfterIntent(Trade trade, Order buyOrder, Order sellOrder, int chunk,
                                      int remainingAfter, long now) {
        String ref = trade.escrowReference();
        if (ref == null || ref.isBlank()) {
            LOGGER.error("settle after intent: trade {} без ref", trade.tradeId());
            return false;
        }
        long gross = trade.grossMinor();
        long commission = trade.commissionMinor();
        long sellerNet = gross - commission;
        long locked;
        try {
            // remainingAfter одинаково трактуется и сразу после intent, и при recovery.
            locked = Math.multiplyExact((long) remainingAfter + chunk,
                    buyOrder.pricePerUnit());
        } catch (ArithmeticException e) {
            LOGGER.error("escrow amount overflow: {}", trade.tradeId());
            markManualReview(buyOrder, trade.tradeId(), "overflow escrow");
            return false;
        }
        long refund = locked - gross;
        if (refund < 0) {
            LOGGER.error("refund<0 для {} (locked={}, gross={})", trade.tradeId(), locked, gross);
            markManualReview(buyOrder, trade.tradeId(), "refund<0");
            return false;
        }

        // S3: settlement; нулевые доли не отправляются (#9)
        List<EconomyGateway.Credit> credits = new ArrayList<>(3);
        if (sellerNet > 0) {
            credits.add(new EconomyGateway.Credit(sellOrder == null ? trade.sellerUuid() : sellOrder.ownerUuid(),
                    sellerNet, ROLE_SELLER));
        }
        if (commission > 0) {
            credits.add(new EconomyGateway.Credit(economy.treasury(), commission, ROLE_COMMISSION));
        }
        if (refund > 0) {
            credits.add(new EconomyGateway.Credit(trade.buyerUuid(), refund, ROLE_BUYER_REFUND));
        }
        EconomyGateway.SettleResult settled = economy.settle(ref, credits,
                "settle " + trade.tradeId(), "va:settle:" + trade.tradeId());
        if (!settled.isSuccessOrIdempotent()) {
            LOGGER.warn("S3 settle failed {}: {}", trade.tradeId(), settled.status());
            markManualReview(buyOrder, trade.tradeId(), "settle " + settled.status());
            return false;
        }

        // S4a: новая эпоха резерва (только если остался невыполненный остаток)
        boolean advance = remainingAfter > 0;
        int nextEpoch = buyOrder.refEpoch() + 1;
        String nextRef;
        if (advance) {
            long nextLocked;
            try {
                nextLocked = Math.multiplyExact((long) remainingAfter, buyOrder.pricePerUnit());
            } catch (ArithmeticException e) {
                markManualReview(buyOrder, trade.tradeId(), "overflow next escrow");
                return false;
            }
            nextRef = refFor(buyOrder.orderId(), nextEpoch);
            EconomyGateway.ReserveResult relock = economy.reserve(trade.buyerUuid(), nextLocked,
                    nextRef, "buy hold " + buyOrder.orderId() + " e" + nextEpoch,
                    "va:relock:" + trade.tradeId());
            if (!relock.isSuccessOrIdempotent()) {
                LOGGER.warn("S4 relock failed {}: {}", trade.tradeId(), relock.status());
                // Текущий fill уже оплачен атомарным settlement. Его нужно завершить и
                // выдать покупателю; небезопасен только НЕИСПОЛНЕННЫЙ остаток BUY.
                markManualReview(buyOrder, trade.tradeId(), "relock " + relock.status());
                advance = false;
                nextRef = null;
            }
        } else {
            nextRef = null;
        }

        // S4b: финальная фиксация (транзакция; повторимая)
        boolean shouldAdvance = advance;
        String committedRef = nextRef;
        try {
            database.inTransaction(conn -> {
                Order pendingOrder = orders.findById(conn, buyOrder.orderId()).orElse(buyOrder);
                if (shouldAdvance && pendingOrder.isActive()) {
                    orders.applyState(conn, pendingOrder,
                            pendingOrder.withRefEpoch(nextEpoch, committedRef, now));
                }
                Trade current = trades.findById(conn, trade.tradeId()).orElse(trade);
                trades.markSettled(conn, current, now);
                insertClaimableDeliveries(conn, trade.buyerUuid(), "fill-" + trade.tradeId(),
                        DeliveryType.PURCHASED, sellOrder == null ? buyOrder.item() : sellOrder.item(),
                        chunk, now);
                completeOp(conn, "fill-" + trade.tradeId());
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("S4 фиксация failed (восстановимо recovery'ем): {}", e.getMessage());
            return false;
        }
        return true;
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
                                reason == null ? "op:cancel:" + orderId : "op:cancel:" + orderId,
                                orderId, actorId, OperationPhase.COMPLETE, now)
                        .operationId("cancel-order-" + orderId).build());
                if (order.side() == OrderSide.SELL && order.remainingQuantity() > 0) {
                    insertClaimableDeliveries(conn, actorId, "cancel-order-" + orderId,
                            DeliveryType.CANCELLED_RETURN, order.item(), order.remainingQuantity(), now);
                }
                if (order.side() == OrderSide.BUY
                        && order.escrowReference() != null
                        && !order.escrowReference().isBlank()) {
                    EconomyGateway.ReleaseResult rel = economy.release(order.escrowReference(),
                            "cancel buy " + orderId, "va:cancel:" + orderId);
                    if (!rel.isSuccessOrIdempotent()) {
                        throw new DatabaseException("release при cancel " + orderId
                                + " завершился " + rel.status());
                    }
                }
                completeOp(conn, "cancel-order-" + orderId);
                return Outcome.ok("Ордер отменён", cancelled, List.of());
            });
        } catch (RuntimeException e) {
            return Outcome.fail(Result.DATABASE_FAILED, e.getMessage());
        }
    }

    // ================================================================ claim

    /**
     * Выдача письма. Разрешена ТОЛЬКО из CLAIMABLE (#7: delivery создаётся
     * только после settlement) — предмет невозможно получить раньше выплат.
     * Промежуточные CAS всегда идут от свежих объектов БД (#8).
     */
    public Outcome claimDelivery(UUID actorId, long deliveryId) {
        long now = now();
        AuctionDelivery found = database.query(c -> deliveries.findById(c, deliveryId).orElse(null));
        if (found == null) {
            return Outcome.fail(Result.ORDER_NOT_FOUND, "Письмо не найдено");
        }
        if (!found.playerUuid().equals(actorId)) {
            return Outcome.fail(Result.NOT_YOUR_ORDER, "Чужое письмо");
        }
        if (found.state() != DeliveryState.CLAIMABLE) {
            if (found.state() == DeliveryState.PENDING) {
                return Outcome.fail(Result.ORDER_NOT_FOUND, "Письмо ещё не готово к выдаче");
            }
            return Outcome.fail(Result.ORDER_NOT_FOUND, "Письмо уже выдано");
        }
        AuctionDelivery claiming = found.toClaiming(now);
        boolean locked = database.inTransaction(c -> deliveries.applyState(c, found, claiming));
        if (!locked) {
            return Outcome.fail(Result.DATABASE_FAILED, "Письмо уже забирается другим вызовом");
        }
        // свежая версия после CAS — следующий переход должен видеть version+1 (#8)
        AuctionDelivery held = database.query(c -> deliveries.findById(c, deliveryId)
                .orElse(claiming));
        if (held.state() != DeliveryState.CLAIMING) {
            return Outcome.fail(Result.DATABASE_FAILED, "Письмо сменило состояние");
        }
        ItemStack stack;
        try {
            stack = codec.decode(held.item());
        } catch (ItemCodecException e) {
            LOGGER.warn("claim {} decode failed: {}", deliveryId, e.getMessage());
            database.inTransaction(c -> deliveries.applyState(c, held, held.toFailed("decode failed")));
            return Outcome.fail(Result.DATABASE_FAILED, "Не удалось восстановить предмет");
        }
        if (stack.isEmpty()) {
            database.inTransaction(c -> deliveries.applyState(c, held, held.toFailed("decode failed")));
            return Outcome.fail(Result.DATABASE_FAILED, "Не удалось восстановить предмет");
        }
        ItemStack leftover = inventory.give(actorId, stack);
        if (!leftover.isEmpty()) {
            database.inTransaction(c -> deliveries.applyState(c, held,
                    held.reopenClaimable(now, "inventory full")));
            return Outcome.fail(Result.INVENTORY_FULL, "Инвентарь полон");
        }
        boolean completed = database.inTransaction(c ->
                deliveries.applyState(c, held, held.toClaimed(now)));
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
            insertClaimableDeliveries(conn, order.ownerUuid(), "expire-order-" + order.orderId(),
                    DeliveryType.CANCELLED_RETURN, order.item(), order.remainingQuantity(), now);
        }
        if (order.side() == OrderSide.BUY && order.escrowReference() != null
                && !order.escrowReference().isBlank()) {
            EconomyGateway.ReleaseResult rel = economy.release(order.escrowReference(),
                    "expire buy " + order.orderId(), "va:expire:" + order.orderId());
            if (!rel.isSuccessOrIdempotent()) {
                throw new DatabaseException("release при expire " + order.orderId()
                        + " завершился " + rel.status());
            }
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

    // ================================================================ manual review

    /** Внешний перевод ордера в ручное ревью (для RecoveryService). */
    public void forceOrderManualReview(UUID orderId, String reason) {
        try {
            database.inTransaction(c -> {
                Order order = orders.findById(c, orderId).orElse(null);
                if (order == null || !order.isActive()) {
                    return null;
                }
                orders.applyState(c, order, order.toManualReview(now()));
                operations.insert(c, operationEntry(OperationType.RECOVERY,
                                "op:manual-review:" + orderId, orderId, null,
                                OperationPhase.BEGIN, now())
                        .operationId("manual-review-" + orderId).build());
                return null;
            });
            LOGGER.warn("Ордер {} отправлен на ручное ревью: {}", orderId, reason);
        } catch (RuntimeException e) {
            LOGGER.warn("forceOrderManualReview {} failed: {}", orderId, e.getMessage());
        }
    }

    /**
     * Карантин зависшего CLAIMING. После рестарта неизвестно, успел ли Minecraft
     * сохранить уже выданный предмет, поэтому автоматический повтор небезопасен.
     * FAILED требует ручной сверки и исключает дюп.
     */
    public boolean quarantineClaim(long deliveryId) {
        return database.inTransaction(c -> {
            AuctionDelivery d = deliveries.findById(c, deliveryId).orElse(null);
            if (d == null || d.state() != DeliveryState.CLAIMING) {
                return true;
            }
            return deliveries.applyState(c, d, d.toFailed(
                    "indeterminate claim after restart; manual review required"));
        });
    }

    // ================================================================ helpers

    private void markManualReview(Order order, UUID tradeId, String reason) {
        long now = now();
        try {
            database.inTransaction(c -> {
                Order fresh = orders.findById(c, order.orderId()).orElse(order);
                if (fresh.isActive()) {
                    orders.applyState(c, fresh, fresh.toManualReview(now));
                }
                operations.findById(c, "fill-" + tradeId)
                        .ifPresent(op -> operations.applyRetry(c, op.operationId(),
                                op.attemptCount(), op.toManualReview(reason, now())));
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("MANUAL_REVIEW mark failed: {}", e.getMessage());
        }
    }

    private void markCreateBuyFailed(UUID orderId, String reason) {
        long now = now();
        try {
            database.inTransaction(c -> {
                Order fresh = orders.findById(c, orderId).orElse(null);
                if (fresh != null && fresh.isActive()) {
                    orders.applyState(c, fresh, fresh.toManualReview(now));
                }
                operations.findById(c, "create-buy-" + orderId)
                        .ifPresent(op -> operations.applyRetry(c, op.operationId(),
                                op.attemptCount(), op.toManualReview(reason, now)));
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.error("Не удалось остановить необеспеченный BUY {}: {}", orderId,
                    e.getMessage(), e);
        }
    }

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

    private long commissionOf(long makerPrice, int chunk) {
        long gross = Math.multiplyExact(makerPrice, (long) chunk);
        long bps = settings.commissionBps();
        return Math.addExact(Math.multiplyExact(gross / 10_000L, bps),
                Math.multiplyExact(gross % 10_000L, bps) / 10_000L);
    }

    private static String refFor(UUID buyOrderId, int epoch) {
        return REF_BUY + buyOrderId + ":" + epoch;
    }

    private static UUID deterministicTradeId(UUID buyOrderId, UUID sellOrderId, int epoch) {
        return UUID.nameUUIDFromBytes((buyOrderId + ":" + sellOrderId + ":" + epoch)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Перекодирует снимок под нужное количество (blob и hash согласованы — decode().quantity совпадёт, #5). */
    private ItemSnapshot withQuantity(ItemSnapshot unit, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (quantity == unit.quantity()) {
            return unit;
        }
        try {
            ItemStack stack = codec.decode(unit);
            stack.setCount(quantity);
            return codec.encode(stack);
        } catch (ItemCodecException e) {
            throw new DatabaseException("не удалось перекодировать количество " + quantity, e);
        }
    }

    /**
     * Вставка письма (писем) с корректно сериализованным количеством, по частям
     * не больше max stack size предмета и 64.
     * Письма сразу CLAIMABLE, dedupeKey уникален (повтор recovery не дублирует).
     */
    private void insertClaimableDeliveries(Connection conn, UUID playerUuid, String baseOpId,
                                           DeliveryType type, ItemSnapshot unit, int quantity, long now) {
        int remaining = quantity;
        int part = 0;
        int maxChunk;
        try {
            maxChunk = Math.max(1, Math.min(MAX_DELIVERY_CHUNK, codec.decode(unit).getMaxStackSize()));
        } catch (ItemCodecException e) {
            throw new DatabaseException("не удалось определить размер delivery-стека", e);
        }
        while (remaining > 0) {
            int q = Math.min(maxChunk, remaining);
            String dedupe = baseOpId + (part == 0 ? "" : ":" + part);
            if (deliveries.findByDedupeKey(conn, dedupe).isEmpty()) {
                deliveries.insert(conn, AuctionDelivery.newDelivery(playerUuid, 0L, baseOpId, type,
                                withQuantity(unit, q), now)
                        .dedupeKey(dedupe)
                        .state(DeliveryState.CLAIMABLE)
                        .build());
            }
            remaining -= q;
            part++;
        }
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

    private static long now() {
        return System.currentTimeMillis();
    }
}

package com.valorcraft.vauction.application;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.domain.buyorder.BuyOrder;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.domain.listing.AuctionListing;
import com.valorcraft.vauction.domain.listing.ListingStatus;
import com.valorcraft.vauction.domain.operation.AuctionOperation;
import com.valorcraft.vauction.domain.operation.OperationType;
import com.valorcraft.vauction.domain.sale.AuctionSale;
import com.valorcraft.vauction.economy.VEconomyBridge;
import com.valorcraft.vauction.item.ItemCodecException;
import com.valorcraft.vauction.item.ItemPolicy;
import com.valorcraft.vauction.item.ItemSnapshot;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.persistence.BuyOrderRepository;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.ListingRepository;
import com.valorcraft.vauction.persistence.OperationRepository;
import com.valorcraft.vauction.persistence.SaleRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Бизнес-логика аукциона (порт ExchangeService на нашу БД/домен).
 * Все операции — на главном потоке сервера. Каждая операция, меняющая деньги
 * VEconomy, проверяет возвращаемые значения; при сбое — возврат средств/предметов
 * (компенсация вручную, как у донора). Операции с заявкой идемпотентны по ключам
 * транзакций VEconomy (защита от дупов при повторах).
 * <p>
 * Лот в нашей модели — это одна стопка предметов целиком (quantity из снимка),
 * цена лота — полная ({@code priceMinor}). Покупка из лота — только всей стопки.
 */
public final class AuctionService {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    public enum Result {
        SUCCESS,
        NOT_A_PLAYER,
        ORDER_NOT_FOUND,
        NOT_YOUR_ORDER,
        SELF_TRADE,
        INVALID_QUANTITY,
        INVALID_PRICE,
        OVER_LIMIT,
        BLACKLISTED,
        INSUFFICIENT_FUNDS,
        INVENTORY_FULL,
        ECONOMY_FAILED,
        DATABASE_FAILED
    }

    /** Результат операции: статус + сообщение игроку (null — без сообщения). */
    public record Outcome(Result status, String message) {
        public static Outcome ok(String message) {
            return new Outcome(Result.SUCCESS, message);
        }

        public boolean isSuccess() {
            return status == Result.SUCCESS;
        }
    }

    private final DatabaseManager database;
    private final ListingRepository listings;
    private final BuyOrderRepository buyOrders;
    private final SaleRepository sales;
    private final DeliveryRepository deliveries;
    private final OperationRepository operations;
    private final ItemStackCodec codec;
    private final ListingService listingService;
    private final AuctionSettings settings;

    public AuctionService(DatabaseManager database, ListingRepository listings,
                          BuyOrderRepository buyOrders, SaleRepository sales,
                          DeliveryRepository deliveries, OperationRepository operations,
                          ItemStackCodec codec, ListingService listingService, AuctionSettings settings) {
        this.database = database;
        this.listings = listings;
        this.buyOrders = buyOrders;
        this.sales = sales;
        this.deliveries = deliveries;
        this.operations = operations;
        this.codec = codec;
        this.listingService = listingService;
        this.settings = settings;
    }

    private String reason(String detail) {
        return VEconomyBridge.reason(detail);
    }

    // ================================================================ создание лота на продажу

    /**
     * Выставить лот из слота инвентаря игрока. Перед созданием матчимся с заявками
     * на покупку: сначала самые дорогие для продавца. Остаток — в лот.
     */
    public Outcome createSellOrder(ServerPlayer player, int slotIndex, long pricePerUnit, int quantity) {
        if (player == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эта операция доступна только игрокам.");
        }
        if (quantity <= 0 || slotIndex < 0 || slotIndex >= player.getInventory().getContainerSize()) {
            return new Outcome(Result.INVALID_QUANTITY, "Некорректные параметры лота.");
        }
        if (pricePerUnit <= 0) {
            return new Outcome(Result.INVALID_PRICE, "Цена должна быть положительной.");
        }
        ItemStack stack = player.getInventory().getItem(slotIndex);
        if (stack.isEmpty()) {
            return new Outcome(Result.INVALID_QUANTITY, "В этом слоте пусто.");
        }
        if (stack.getCount() < quantity || !ItemPolicy.check(stack, settings).allowed()) {
            return new Outcome(Result.INVALID_QUANTITY, "В слоте недостаточно предметов или они запрещены.");
        }
        int activeForSeller = database.query(c -> listings.activeFor(c, player.getUUID()).size());
        if (activeForSeller >= settings.maxActiveListingsPerPlayer()) {
            return new Outcome(Result.OVER_LIMIT,
                    "Достигнут лимит лотов (" + settings.maxActiveListingsPerPlayer() + ").");
        }

        ItemStack sample = stack.copy();
        sample.setCount(1);

        // Матчинг с заявками на покупку: сначала самые дорогие для продавца.
        List<BuyOrder> candidates = new ArrayList<>(database.query(
                c -> buyOrders.activeByRegistryId(c, sample.getItem().toString())));
        candidates.sort(Comparator.comparingLong((BuyOrder b) -> b.pricePerUnit()).reversed());

        int matchedCount = 0;
        for (BuyOrder buy : candidates) {
            int want = quantity - matchedCount;
            if (want <= 0) {
                break;
            }
            if (buy.pricePerUnit() < pricePerUnit) {
                continue;
            }
            if (!matches(sample, buy.item())) {
                continue;
            }
            int chunk = Math.min(want, buy.remaining());
            long earnings = buy.pricePerUnit() * (long) chunk;
            Outcome fulfilled = fulfillBuyOrderInternal(player, buy, chunk, earnings);
            if (fulfilled.isSuccess()) {
                matchedCount += chunk;
            }
        }

        int remainingForLot = quantity - matchedCount;
        if (remainingForLot > 0) {
            reduceSlot(player, slotIndex, remainingForLot);
            ItemStack lotStack = sample.copy();
            lotStack.setCount(remainingForLot);
            ListingService.ListingCreateResult created = listingService.createListing(
                    player.getUUID(), lotStack, pricePerUnit * (long) remainingForLot, settings);
            if (!created.success()) {
                return new Outcome(Result.DATABASE_FAILED,
                        "Не удалось создать лот: " + created.detail());
            }
            return Outcome.ok("Лот выставлен: x" + remainingForLot + " по " + pricePerUnit);
        }
        return Outcome.ok("Весь объём продан по заявкам на покупку");
    }

    private static void reduceSlot(ServerPlayer player, int slotIndex, int count) {
        ItemStack stack = player.getInventory().getItem(slotIndex);
        stack.shrink(count);
        if (stack.getCount() <= 0) {
            player.getInventory().setItem(slotIndex, ItemStack.EMPTY);
        }
        player.getInventory().setChanged();
    }

    // ================================================================ покупка лота целиком

    /** Купить лот {@code listingId} целиком (вся стопка). */
    public Outcome buyFromListing(ServerPlayer buyer, long listingId) {
        if (buyer == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту операцию может выполнить только игрок.");
        }
        AuctionListing listing = database.query(c -> listings.findById(c, listingId).orElse(null));
        if (listing == null || listing.status() != ListingStatus.ACTIVE) {
            return new Outcome(Result.ORDER_NOT_FOUND, "Лот не найден или уже продан.");
        }
        if (listing.sellerUuid().equals(buyer.getUUID())) {
            return new Outcome(Result.SELF_TRADE, "Нельзя покупать собственный лот.");
        }
        long totalPrice = listing.priceMinor();
        if (!VEconomyBridge.has(buyer.getUUID(), totalPrice)) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, "Недостаточно средств на балансе.");
        }

        // 1. Списание с покупателя (идемпотентно по operationId).
        String opId = "bl-" + UUID.randomUUID();
        if (!VEconomyBridge.withdraw(buyer.getUUID(), totalPrice,
                reason("покупка лота #" + listingId), opId)) {
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось списать средства.");
        }

        // 2. Выдача предметов (инвентарь → почта).
        giveItems(buyer.getServer(), buyer.getUUID(), listing.item(), opId);

        // 3. Комиссия + выплата продавцу (продавец может быть офлайн).
        long sellerNet = commissionAndNet(totalPrice, listing.commissionBps());
        long commission = totalPrice - sellerNet;
        if (!VEconomyBridge.deposit(listing.sellerUuid(), sellerNet,
                reason("продажа лота #" + listingId), opId + ":sell")) {
            VEconomyBridge.deposit(buyer.getUUID(), totalPrice,
                    reason("откат лота #" + listingId), opId + ":refund");
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось выплатить продавцу; покупка отменена.");
        }

        // 4. Состояние лота SOLD + запись продажи + журнал — одной транзакцией.
        try {
            long soldAt = System.currentTimeMillis();
            database.inTransaction(c -> {
                AuctionListing reserved = listing.toReserved(buyer.getUUID(),
                        "vauction:sale:" + listingId, soldAt, soldAt + Duration.ofMinutes(5).toMillis(), soldAt);
                AuctionListing sold = reserved.toSold(buyer.getUUID(), soldAt);
                if (!listings.applyState(c, listing, reserved)
                        || !listings.applyState(c, reserved, sold)) {
                    throw new IllegalStateException("listing concurrent modification: " + listingId);
                }
                sales.insert(c, AuctionSale.newSale(listing.sellerUuid(), buyer.getUUID(), totalPrice,
                                "vauction:sale:" + listingId, listing.item().hash(), soldAt)
                        .purchaseOperationId(opId)
                        .listingId(listingId)
                        .commissionMinor(commission)
                        .sellerNetMinor(sellerNet)
                        .build());
                operations.insert(c, AuctionOperation
                        .newOperation(OperationType.BUY_FROM_LISTING,
                                "buy:" + listingId + ":" + buyer.getUUID(), soldAt)
                        .operationId(opId)
                        .listingId(listingId)
                        .actor(buyer.getUUID())
                        .build());
                return null;
            });
        } catch (Exception e) {
            LOGGER.error("Ошибка записи продажи лота {}: {}", listingId, e.getMessage(), e);
            return new Outcome(Result.DATABASE_FAILED, "Продажа не записана; обратитесь к администратору.");
        }
        return Outcome.ok("Куплено: +" + listing.item().quantity() + " " + listing.item().displayName());
    }

    // ================================================================ создание заявки на покупку

    /** Заявка на покупку: сначала мгновенный матчинг с лотами, заявка — на остаток. */
    public Outcome createBuyOrder(ServerPlayer buyer, ItemStack stack, long pricePerUnit, int totalAmount) {
        if (buyer == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту операцию может выполнить только игрок.");
        }
        if (totalAmount <= 0 || pricePerUnit <= 0) {
            return new Outcome(Result.INVALID_QUANTITY, "Некорректные параметры заявки.");
        }
        if (stack == null || stack.isEmpty() || !ItemPolicy.check(stack, settings).allowed()) {
            return new Outcome(Result.BLACKLISTED, "Предмет запрещён к торговле (конфиг).");
        }
        long needBalance = Math.multiplyExact(pricePerUnit, (long) totalAmount);
        if (!VEconomyBridge.has(buyer.getUUID(), needBalance)) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, "Недостаточно средств на полную стоимость.");
        }
        int activeForBuyer = database.query(c -> buyOrders.activeForBuyer(c, buyer.getUUID()).size());
        if (activeForBuyer >= settings.maxBuyOrdersPerPlayer()) {
            return new Outcome(Result.OVER_LIMIT,
                    "Достигнут лимит заявок (" + settings.maxBuyOrdersPerPlayer() + ").");
        }

        ItemStack sample = stack.copy();
        sample.setCount(1);
        ItemSnapshot snapshot;
        try {
            snapshot = codec.encode(sample);
        } catch (ItemCodecException e) {
            return new Outcome(Result.BLACKLISTED, "Предмет не может быть закодирован: " + e.getMessage());
        }

        // 1. Мгновенный матчинг: покупаем у лотов по цене <= заявки (дешёвые раньше).
        int bought = matchWithLots(buyer, sample, pricePerUnit, totalAmount);

        // 2. Заявка — только на остаток.
        int remainingAmount = totalAmount - bought;
        if (remainingAmount <= 0) {
            return Outcome.ok("Заявка исполнена мгновенно из лотов (" + bought + " шт).");
        }
        long freezeAmount = Math.multiplyExact(pricePerUnit, (long) remainingAmount);
        if (!VEconomyBridge.has(buyer.getUUID(), freezeAmount)) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, "Недостаточно средств после покупки из лотов.");
        }

        BuyOrder order = BuyOrder.newOrder(buyer.getUUID(), snapshot, pricePerUnit, remainingAmount,
                System.currentTimeMillis()).build();
        if (!VEconomyBridge.freezeFunds(buyer.getUUID(), freezeAmount,
                order.escrowReference(),
                reason("заморозка заявки #" + order.buyOrderId()), order.buyOrderId() + ":freeze:0")) {
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось заморозить средства.");
        }
        try {
            database.inTransaction(c -> {
                buyOrders.insert(c, order);
                operations.insert(c, AuctionOperation
                        .newOperation(OperationType.CREATE_BUY_ORDER,
                                "buy-order:" + order.buyOrderId(), System.currentTimeMillis())
                        .operationId("bo-" + order.buyOrderId())
                        .actor(buyer.getUUID())
                        .build());
                return null;
            });
        } catch (Exception e) {
            VEconomyBridge.unfreezeRefund(order.escrowReference(),
                    reason("откат заявки #" + order.buyOrderId()), order.buyOrderId() + ":refund:0");
            return new Outcome(Result.DATABASE_FAILED, "Заявка не сохранена; заморозка возвращена.");
        }
        return Outcome.ok("Заявка размещена: " + snapshot.displayName() + " x" + remainingAmount
                + " по " + pricePerUnit);
    }

    private int matchWithLots(ServerPlayer buyer, ItemStack sample, long pricePerUnit, int totalAmount) {
        String registryId = sample.getItem().toString();
        List<AuctionListing> candidates = new ArrayList<>(database.query(
                c -> listings.activeByRegistryId(c, registryId)));
        candidates.sort(Comparator.comparingLong(o -> unitPrice(o)));
        int bought = 0;
        for (AuctionListing sell : candidates) {
            int need = totalAmount - bought;
            if (need <= 0) {
                break;
            }
            if (!matches(sample, sell.item())) {
                continue;
            }
            long unit = unitPrice(sell);
            if (unit > pricePerUnit) {
                continue;
            }
            Outcome res = buyFromListing(buyer, sell.listingId());
            if (res.isSuccess()) {
                bought += sell.item().quantity();
            }
        }
        return bought;
    }

    private static long unitPrice(AuctionListing listing) {
        int qty = listing.item().quantity();
        return qty <= 0 ? listing.priceMinor() : listing.priceMinor() / qty;
    }

    // ================================================================ исполнение заявки продавцом

    /** Продавец исполняет заявку {@code buyOrderId} на {@code amountToSell} единиц. */
    public Outcome fulfillBuyOrder(ServerPlayer executor, UUID buyOrderId, int amountToSell) {
        if (executor == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту операцию может выполнить только игрок.");
        }
        if (amountToSell <= 0) {
            return new Outcome(Result.INVALID_QUANTITY, "Количество должно быть положительным.");
        }
        BuyOrder order = database.query(c -> buyOrders.findById(c, buyOrderId).orElse(null));
        if (order == null || !order.active()) {
            return new Outcome(Result.ORDER_NOT_FOUND, "Заявка не найдена или завершена.");
        }
        if (order.buyerUuid().equals(executor.getUUID())) {
            return new Outcome(Result.SELF_TRADE, "Нельзя исполнять собственную заявку.");
        }
        int toSell = Math.min(amountToSell, order.remaining());
        ItemStack sample = decodeOrNull(order.item());
        if (sample == null || countInInventory(executor, sample) < toSell) {
            return new Outcome(Result.INVALID_QUANTITY, "Недостаточно предметов в инвентаре.");
        }
        long earnings = order.pricePerUnit() * (long) toSell;
        return fulfillBuyOrderInternal(executor, order, toSell, earnings);
    }

    /**
     * Общая реализация передачи по заявке (матчинг при создании лота и исполнение
     * заявки продавцом). Предметы снимаются у исполнителя (продавца), деньги
     * покупателя — из нативного эскроу VEconomy.
     * <p>
     * Эскроу работает по reference целиком, поэтому применяется схема донора:
     * release всей заморозки → списание доли → выплата продавцу → повторная
     * заморозка остатка под новый reference (epoch+1). Каждый шаг проверяется;
     * при сбое — предметы возвращаются исполнителю, заявка живёт корректно.
     */
    private Outcome fulfillBuyOrderInternal(ServerPlayer executor, BuyOrder order,
                                            int toSell, long earnings) {
        UUID buyerId = order.buyerUuid();
        int newFulfilled = order.fulfilledAmount() + toSell;
        boolean done = newFulfilled >= order.totalRequested();
        long remainingAfter = order.pricePerUnit() * (long) (order.totalRequested() - newFulfilled);
        ItemStack sample = decodeOrNull(order.item());
        if (sample == null) {
            return new Outcome(Result.DATABASE_FAILED, "Предмет заявки повреждён.");
        }

        // 1. Предметы от исполнителя.
        removeFromInventory(executor, sample, toSell);

        // 2. Освобождаем всю заморозку заявки.
        String oldRef = order.escrowReference();
        if (!VEconomyBridge.unfreezeRefund(oldRef,
                reason("сброс заявки #" + order.buyOrderId()), order.buyOrderId() + ":release")) {
            addItemsToInventoryOrMailbox(executor, order.item(), toSell);
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось снять заморозку заявки.");
        }

        // 3. Покупатель оплачивает исполненную часть.
        if (!VEconomyBridge.withdraw(buyerId, earnings,
                reason("исполнение заявки #" + order.buyOrderId()),
                order.buyOrderId() + ":pay:" + order.refEpoch())) {
            addItemsToInventoryOrMailbox(executor, order.item(), toSell);
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось списать долю покупателя.");
        }

        // 4. Выплата продавцу за вычетом комиссии.
        long sellerNet = commissionAndNet(earnings, settings.commissionBps());
        long commission = earnings - sellerNet;
        if (!VEconomyBridge.deposit(executor.getUUID(), sellerNet,
                reason("выплата заявки #" + order.buyOrderId()),
                order.buyOrderId() + ":sell:" + order.refEpoch())) {
            VEconomyBridge.deposit(buyerId, earnings, reason("откат заявки #" + order.buyOrderId()),
                    order.buyOrderId() + ":refund:" + order.refEpoch());
            addItemsToInventoryOrMailbox(executor, order.item(), toSell);
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось выплатить продавцу.");
        }

        // 5. Почта покупателя (идемпотентно по buyOrderId+epoch).
        giveItems(executor.getServer(), buyerId, order.item(),
                order.buyOrderId() + ":buy:" + order.refEpoch());

        // 6. Состояние заявки (CAS).
        try {
            database.inTransaction(c -> {
                if (done) {
                    if (!buyOrders.applyState(c, order, order.deactivate(System.currentTimeMillis()))) {
                        throw new IllegalStateException("buy order concurrent modification: " + order.buyOrderId());
                    }
                } else {
                    int nextEpoch = order.refEpoch() + 1;
                    String newRef = order.escrowReference();
                    String newRefStr = "vauction:buy:" + order.buyOrderId() + ":" + nextEpoch;
                    if (!VEconomyBridge.freezeFunds(buyerId, remainingAfter, newRefStr,
                            reason("заморозка остатка #" + order.buyOrderId()),
                            order.buyOrderId() + ":freeze:" + nextEpoch)) {
                        throw new IllegalStateException("refreeze failed: " + order.buyOrderId());
                    }
                    if (!buyOrders.applyState(c, order,
                            order.markFulfilled(toSell, nextEpoch, System.currentTimeMillis()))) {
                        throw new IllegalStateException("buy order concurrent modification: " + order.buyOrderId());
                    }
                }
                operations.insert(c, AuctionOperation
                        .newOperation(OperationType.FULFILL_BUY_ORDER,
                                "fulfill:" + order.buyOrderId() + ":" + order.refEpoch(),
                                System.currentTimeMillis())
                        .operationId("fo-" + order.buyOrderId() + "-" + order.refEpoch())
                        .actor(executor.getUUID())
                        .build());
                return null;
            });
        } catch (Exception e) {
            LOGGER.error("Сбой фиксации исполнения заявки {}: {}", order.buyOrderId(), e.getMessage(), e);
            addItemsToInventoryOrMailbox(executor, order.item(), toSell);
            return new Outcome(Result.DATABASE_FAILED, "Заявка исполнена, но не зафиксирована; предметы возвращены.");
        }
        return Outcome.ok("Заявка исполнена: x" + toSell);
    }

    // ================================================================ отмены

    /** Отмена лота: возврат предметов продавцу. */
    public Outcome cancelListing(ServerPlayer player, long listingId) {
        if (player == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту операцию может выполнить только игрок.");
        }
        AuctionListing listing = database.query(c -> listings.findById(c, listingId).orElse(null));
        if (listing == null) {
            return new Outcome(Result.ORDER_NOT_FOUND, "Лот не найден.");
        }
        if (!listing.sellerUuid().equals(player.getUUID())) {
            return new Outcome(Result.NOT_YOUR_ORDER, "Это чужой лот.");
        }
        try {
            database.inTransaction(c -> {
                AuctionListing cancelled = listing.toCancelled("seller cancel", null, System.currentTimeMillis());
                if (!listings.applyState(c, listing, cancelled)) {
                    throw new IllegalStateException("listing concurrent modification: " + listingId);
                }
                operations.insert(c, AuctionOperation
                        .newOperation(OperationType.CANCEL_LISTING,
                                "cancel:" + listingId, System.currentTimeMillis())
                        .operationId("cl-" + listingId + "-" + UUID.randomUUID())
                        .listingId(listingId)
                        .actor(player.getUUID())
                        .build());
                return null;
            });
        } catch (Exception e) {
            return new Outcome(Result.DATABASE_FAILED, "Не удалось отменить лот.");
        }
        addItemsToInventoryOrMailbox(player, listing.item(), listing.item().quantity());
        return Outcome.ok("Лот отменён, предметы возвращены.");
    }

    /** Отмена заявки: возврат замороженного остатка покупателю. */
    public Outcome cancelBuyOrder(ServerPlayer player, UUID buyOrderId) {
        if (player == null) {
            return new Outcome(Result.NOT_A_PLAYER, "Эту операцию может выполнить только игрок.");
        }
        BuyOrder order = database.query(c -> buyOrders.findById(c, buyOrderId).orElse(null));
        if (order == null) {
            return new Outcome(Result.ORDER_NOT_FOUND, "Заявка не найдена.");
        }
        if (!order.buyerUuid().equals(player.getUUID())) {
            return new Outcome(Result.NOT_YOUR_ORDER, "Нельзя отменить чужую заявку.");
        }
        if (!VEconomyBridge.unfreezeRefund(order.escrowReference(),
                reason("отмена заявки #" + buyOrderId), buyOrderId + ":cancel:" + order.refEpoch())) {
            return new Outcome(Result.ECONOMY_FAILED, "Не удалось вернуть замороженные средства.");
        }
        try {
            database.inTransaction(c -> {
                if (!buyOrders.applyState(c, order, order.deactivate(System.currentTimeMillis()))) {
                    throw new IllegalStateException("buy order concurrent modification: " + buyOrderId);
                }
                operations.insert(c, AuctionOperation
                        .newOperation(OperationType.CANCEL_BUY_ORDER,
                                "cancel-buy:" + buyOrderId, System.currentTimeMillis())
                        .operationId("cb-" + buyOrderId)
                        .actor(player.getUUID())
                        .build());
                return null;
            });
        } catch (Exception e) {
            return new Outcome(Result.DATABASE_FAILED, "Заявка отменена, но не зафиксирована.");
        }
        return Outcome.ok("Заявка отменена, заморозка возвращена.");
    }

    // ================================================================ почта

    /** Забрать почту в инвентарь; возвращает число забранных стаков. */
    public int claimMailbox(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        List<AuctionDelivery> pending = database.query(c -> deliveries.pendingForPlayer(c, player.getUUID()));
        int claimed = 0;
        for (AuctionDelivery delivery : pending) {
            ItemStack stack = decodeOrNull(delivery.item());
            if (stack == null) {
                continue;
            }
            ItemStack rest = placeStack(player, stack);
            if (rest.isEmpty()) {
                try {
                    database.inTransaction(c -> {
                        long now = System.currentTimeMillis();
                        String token = "claim-" + delivery.deliveryId() + "-" + UUID.randomUUID();
                        AuctionDelivery claimable = delivery.toClaimable(now, token);
                        AuctionDelivery claiming = claimable.toClaiming(now);
                        AuctionDelivery claimedD = claiming.toClaimed(now);
                        if (!deliveries.applyState(c, delivery, claimable)
                                || !deliveries.applyState(c, claimable, claiming)
                                || !deliveries.applyState(c, claiming, claimedD)) {
                            throw new IllegalStateException("delivery concurrent modification: "
                                    + delivery.deliveryId());
                        }
                        operations.insert(c, AuctionOperation
                                .newOperation(OperationType.CLAIM_MAIL,
                                        "claim:" + delivery.deliveryId(), now)
                                .operationId("dm-" + delivery.deliveryId())
                                .actor(player.getUUID())
                                .build());
                        return null;
                    });
                    claimed++;
                } catch (Exception e) {
                    LOGGER.warn("Не удалось выдать почту {} игроку {}: {}",
                            delivery.deliveryId(), player.getUUID(), e.getMessage());
                    // предмет уже в инвентаре — возвращаем в почту под новым dedupeKey, чтобы не потерять
                    try {
                        new DeliveryService(database, deliveries).create(player.getUUID(), 0L,
                                "claim-return-" + delivery.deliveryId() + "-" + UUID.randomUUID(),
                                delivery.deliveryType(), encodeOrNull(stack),
                                "claim-return:" + delivery.deliveryId());
                    } catch (Exception ignored) {
                    }
                }
            } else {
                // Инвентарь полон — попробуем ещё раз позже.
            }
        }
        return claimed;
    }

    /** Есть ли у игрока неподученная почта. */
    public boolean hasMail(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return !database.query(c -> deliveries.pendingForPlayer(c, player.getUUID())).isEmpty();
    }

    // ================================================================ выдача предметов

    /** Выдать предметы игроку: сначала инвентарь (онлайн), излишек — в почту (delivery). */
    public void giveItems(MinecraftServer server, UUID playerId, ItemSnapshot snapshot, String dedupeKey) {
        if (server == null || playerId == null || snapshot == null) {
            return;
        }
        ItemStack sample = decodeOrNull(snapshot);
        if (sample == null) {
            LOGGER.error("Не удалось восстановить предмет для выдачи {} игроку {}: снимок повреждён",
                    dedupeKey, playerId);
            return;
        }
        int remaining = sample.getCount();
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            ItemStack copy = sample.copy();
            ItemStack rest = placeStack(online, copy);
            if (rest.isEmpty()) {
                return; // всё поместилось
            }
            remaining = rest.getCount();
            sample = rest;
        }
        // Почта (dedupeKey защищает от дублей при повторе события).
        DeliveryService.DeliveryCreateResult result =
                new DeliveryService(database, deliveries).create(playerId, 0L,
                        "mail-" + dedupeKey, DeliveryType.PURCHASED, encodeOrNull(sample), "mail:" + dedupeKey);
        if (!result.success()) {
            LOGGER.error("Не удалось создать письмо для {} (dedupeKey={}): {}",
                    playerId, dedupeKey, result.detail());
        }
    }

    private void addItemsToInventoryOrMailbox(ServerPlayer player, ItemSnapshot snapshot, int quantity) {
        if (quantity <= 0 || snapshot == null) {
            return;
        }
        ItemStack sample = decodeOrNull(snapshot);
        if (sample == null) {
            return;
        }
        sample = sample.copy();
        sample.setCount(quantity);
        String key = "return-" + player.getUUID() + "-" + UUID.randomUUID();
        ItemStack rest = placeStack(player, sample);
        if (rest.isEmpty()) {
            return;
        }
        new DeliveryService(database, deliveries).create(player.getUUID(), 0L,
                "mail-" + key, DeliveryType.CANCELLED_RETURN, encodeOrNull(rest), "mail:" + key);
    }

    /** Попытаться положить стак в инвентарь: возвращает не влезший остаток (может быть EMPTY). */
    private static ItemStack placeStack(ServerPlayer player, ItemStack stack) {
        int maxStack = Math.max(1, stack.getMaxStackSize());
        ItemStack remainingCopy = stack.copy();
        for (int i = 0; i < player.getInventory().getContainerSize() && !remainingCopy.isEmpty(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty()) {
                ItemStack add = remainingCopy.copy();
                add.setCount(Math.min(remainingCopy.getCount(), maxStack));
                player.getInventory().setItem(i, add);
                remainingCopy.shrink(add.getCount());
            } else if (ItemStack.isSameItemSameTags(slot, remainingCopy) && slot.getCount() < maxStack) {
                int put = Math.min(maxStack - slot.getCount(), remainingCopy.getCount());
                slot.grow(put);
                remainingCopy.shrink(put);
            }
        }
        player.getInventory().setChanged();
        return remainingCopy;
    }

    // ================================================================ комиссия и утилиты

    /** Сумма продавца после вычета комиссии (net). */
    private static long commissionAndNet(long totalPrice, int commissionBps) {
        long commission = totalPrice * commissionBps / 10_000L;
        return Math.max(0, totalPrice - commission);
    }

    /** Совпадают ли предметы (тип + полный NBT) у образца и снимка. */
    private boolean matches(ItemStack sample, ItemSnapshot snapshot) {
        ItemStack decoded = decodeOrNull(snapshot);
        return decoded != null && ItemStack.isSameItemSameTags(sample, decoded);
    }

    private ItemStack decodeOrNull(ItemSnapshot snapshot) {
        try {
            return codec.decode(snapshot);
        } catch (ItemCodecException e) {
            LOGGER.warn("Декодирование предмета не удалось: {}", e.getMessage());
            return null;
        }
    }

    private ItemSnapshot encodeOrNull(ItemStack stack) {
        try {
            return codec.encode(stack);
        } catch (ItemCodecException e) {
            LOGGER.warn("Кодирование предмета не удалось: {}", e.getMessage());
            return null;
        }
    }

    private static void removeFromInventory(ServerPlayer player, ItemStack sample, int count) {
        int need = count;
        for (int i = 0; i < player.getInventory().getContainerSize() && need > 0; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(slot, sample)) {
                int take = Math.min(need, slot.getCount());
                slot.shrink(take);
                need -= take;
                if (slot.getCount() <= 0) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }
        player.getInventory().setChanged();
    }

    private static int countInInventory(ServerPlayer player, ItemStack sample) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(slot, sample)) {
                count += slot.getCount();
            }
        }
        return count;
    }
}

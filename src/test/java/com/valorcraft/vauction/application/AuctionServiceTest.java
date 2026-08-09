package com.valorcraft.vauction.application;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderProcessingState;
import com.valorcraft.vauction.domain.order.OrderStatus;
import com.valorcraft.vauction.domain.trade.TradeState;
import com.valorcraft.vauction.economy.EconomyGateway;
import com.valorcraft.vauction.item.ExactItemMarketKeyStrategy;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.MatchWorkRepository;
import com.valorcraft.vauction.persistence.OperationRepository;
import com.valorcraft.vauction.persistence.OrderRepository;
import com.valorcraft.vauction.persistence.TradeRepository;
import com.valorcraft.vauction.recovery.RecoveryService;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.SharedConstants;
import net.minecraft.DetectedVersion;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionServiceTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge network bootstrap is unavailable in plain JUnit; item registries are ready.
        }
    }

    private DatabaseManager db;
    private OrderRepository orders;
    private DeliveryRepository deliveries;
    private TradeRepository trades;
    private FakeEconomy economy;
    private FakeInventory inventory;
    private AuctionService service;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.openInMemory();
        db.initialize();
        orders = new OrderRepository();
        deliveries = new DeliveryRepository();
        trades = new TradeRepository();
        economy = new FakeEconomy();
        inventory = new FakeInventory();
        ItemStackCodec codec = new ItemStackCodec(262_144, 2_097_152);
        service = new AuctionService(db, orders, trades,
                new OperationRepository(), deliveries, codec,
                new ExactItemMarketKeyStrategy(codec), economy, inventory,
                AuctionSettings.defaults());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void inventorySellUsesExactVariantAcrossSlotsAndDuplicateRequestRemovesOnce() {
        UUID seller = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ItemStack enchanted = new ItemStack(Items.DIAMOND, 1);
        enchanted.getOrCreateTag().putString("variant", "exact");
        inventory.available = 17;

        AuctionService.Outcome first = service.createSellOrderFromInventory(
                seller, enchanted, 50, 17, requestId);
        AuctionService.Outcome duplicate = service.createSellOrderFromInventory(
                seller, enchanted, 50, 17, requestId);

        assertTrue(first.isSuccess());
        assertTrue(duplicate.isSuccess());
        assertEquals(requestId, first.order().orderId());
        assertEquals(1, inventory.takeCalls);
        assertEquals(17, inventory.taken);
        assertEquals("exact", inventory.lastUnit.getTag().getString("variant"));
    }

    @Test
    void insufficientInventorySellDoesNotRemoveAnything() {
        inventory.available = 3;
        AuctionService.Outcome outcome = service.createSellOrderFromInventory(
                UUID.randomUUID(), new ItemStack(Items.IRON_INGOT), 20, 4, UUID.randomUUID());

        assertFalse(outcome.isSuccess());
        assertEquals(AuctionService.Result.INSUFFICIENT_ITEMS, outcome.status());
        assertEquals(0, inventory.takeCalls);
        assertEquals(3, inventory.available);
    }

    @Test
    void fullyBackedBuyFillsAcrossTwoSellersWithFreshEscrowEpochs() {
        UUID buyer = UUID.randomUUID();
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        economy.balances.put(buyer, 10_000L);
        ItemStack copper = new ItemStack(Items.COPPER_INGOT, 1);

        assertTrue(service.createSellOrder(sellerA, copper, 32, 50).isSuccess());
        AuctionService.Outcome buy = service.createBuyOrder(buyer, copper, 35, 100);
        assertTrue(buy.isSuccess());
        assertEquals(1, buy.trades().size());
        assertEquals(32, buy.trades().get(0).executionPrice(), "resting SELL sets maker price");

        Order half = db.query(c -> orders.findById(c, buy.order().orderId())).orElseThrow();
        assertEquals(50, half.remainingQuantity());
        assertEquals(50, half.filledQuantity());
        assertEquals(1, half.refEpoch());
        assertEquals(1_750L, economy.reservedAmount(half.escrowReference()));

        AuctionService.Outcome second = service.createSellOrder(sellerB, copper, 30, 50);
        assertTrue(second.isSuccess());
        assertEquals(1, second.trades().size());
        assertEquals(35, second.trades().get(0).executionPrice(), "resting BUY sets maker price");
        assertNotEquals(buy.trades().get(0).tradeId(), second.trades().get(0).tradeId());
        assertNotEquals(buy.trades().get(0).escrowReference(),
                second.trades().get(0).escrowReference());

        Order filled = db.query(c -> orders.findById(c, buy.order().orderId())).orElseThrow();
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(0, filled.remainingQuantity());
        assertEquals(100, filled.filledQuantity());
        assertEquals(100, filled.remainingQuantity() + filled.filledQuantity());

        List<AuctionDelivery> mail = db.query(c ->
                deliveries.listByState(c, DeliveryState.CLAIMABLE));
        assertEquals(2, mail.size());
        assertEquals(100, mail.stream().mapToInt(d -> d.item().quantity()).sum());
    }

    @Test
    void repeatedGuiBuyConfirmationCreatesOneOrderAndOneReserve() {
        UUID buyer = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        economy.balances.put(buyer, 10_000L);
        ItemStack item = new ItemStack(Items.IRON_INGOT);

        AuctionService.Outcome first = service.createBuyOrder(buyer, item, 25, 4, requestId);
        AuctionService.Outcome second = service.createBuyOrder(buyer, item, 25, 4, requestId);

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(requestId, first.order().orderId());
        assertEquals(requestId, second.order().orderId());
        assertEquals(1, economy.reserveCalls);
        assertEquals(1, db.query(c -> orders.activeForOwner(c, buyer)).size());
    }

    @Test
    void deliveryQuantityDecodesAndDuplicateClaimCannotGiveTwice() {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        economy.balances.put(buyer, 10_000L);
        ItemStack copper = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(seller, copper, 10, 40);
        assertTrue(service.createBuyOrder(buyer, copper, 10, 40).isSuccess());

        AuctionDelivery delivery = db.query(c ->
                deliveries.listByState(c, DeliveryState.CLAIMABLE)).get(0);
        assertEquals(40, delivery.item().quantity());
        assertTrue(service.claimDelivery(buyer, delivery.deliveryId()).isSuccess());
        assertEquals(40, inventory.given);

        assertFalse(service.claimDelivery(buyer, delivery.deliveryId()).isSuccess());
        assertEquals(40, inventory.given, "duplicate claim must not invoke inventory again");
        assertEquals(DeliveryState.CLAIMED,
                db.query(c -> deliveries.findById(c, delivery.deliveryId())).orElseThrow().state());
    }

    @Test
    void zeroCommissionDoesNotCreateInvalidZeroCredit() {
        AuctionSettings d = AuctionSettings.defaults();
        AuctionSettings zeroCommission = new AuctionSettings(
                d.enabled(), d.listingDurationHours(), d.maxActiveListingsPerPlayer(),
                d.maxBuyOrdersPerPlayer(), d.listingFeeMinor(), 0,
                d.expiredRetentionDays(), d.historyRetentionDays(), d.allowSelfPurchase(),
                d.allowContainersWithContents(), d.blockCustomNbt(), d.allowEnchantedBooks(),
                d.maxCompressedItemBytes(), d.maxUncompressedItemBytes(),
                d.sellOrderExpiryDays(), d.buyOrderExpiryDays(), d.itemPolicyMode(),
                d.blockedItems(), d.blockedTags(), d.whitelistedItems(), d.whitelistedTags());
        ItemStackCodec codec = new ItemStackCodec(262_144, 2_097_152);
        service = new AuctionService(db, orders, trades,
                new OperationRepository(), deliveries, codec,
                new ExactItemMarketKeyStrategy(codec), economy, inventory, zeroCommission);
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 10L);
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(UUID.randomUUID(), item, 1, 1);

        AuctionService.Outcome outcome = service.createBuyOrder(buyer, item, 1, 1);
        assertTrue(outcome.isSuccess());
        assertEquals(0L, outcome.trades().get(0).commissionMinor());
        assertEquals(TradeState.SETTLED, outcome.trades().get(0).state());
    }

    @Test
    void rolloverDoesNotDependOnASecondReserveCall() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 10_000L);
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(UUID.randomUUID(), item, 10, 5);
        economy.failReserveAfterCalls = 1;

        AuctionService.Outcome outcome = service.createBuyOrder(buyer, item, 10, 10);
        assertTrue(outcome.isSuccess());
        assertEquals(1, outcome.trades().size());
        assertEquals(TradeState.SETTLED, outcome.trades().get(0).state());
        Order buy = db.query(c -> orders.findById(c, outcome.order().orderId())).orElseThrow();
        assertEquals(OrderStatus.ACTIVE, buy.status());
        assertEquals(5, buy.remainingQuantity());
        assertEquals(1, buy.refEpoch());
        assertEquals(50L, economy.reservedAmount(buy.escrowReference()));
        assertEquals(1, db.query(c ->
                deliveries.listByState(c, DeliveryState.CLAIMABLE)).size());
    }

    @Test
    void indeterminateClaimIsQuarantinedInsteadOfAutomaticallyReopened() {
        UUID player = UUID.randomUUID();
        ItemStackCodec codec = new ItemStackCodec(262_144, 2_097_152);
        long id = db.inTransaction(c -> {
            try {
                var snapshot = codec.encode(new ItemStack(Items.COPPER_INGOT, 4));
                long deliveryId = deliveries.insert(c,
                        com.valorcraft.vauction.domain.delivery.AuctionDelivery
                                .newDelivery(player, 0, "claim-crash",
                                        com.valorcraft.vauction.domain.delivery.DeliveryType.PURCHASED,
                                        snapshot, 1)
                                .dedupeKey("claim-crash").state(DeliveryState.CLAIMABLE).build());
                AuctionDelivery claimable = deliveries.findById(c, deliveryId).orElseThrow();
                deliveries.applyState(c, claimable, claimable.toClaiming(2));
                return deliveryId;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(service.quarantineClaim(id));
        AuctionDelivery quarantined = db.query(c -> deliveries.findById(c, id)).orElseThrow();
        assertEquals(DeliveryState.FAILED, quarantined.state());
        assertEquals(0, inventory.given);
    }

    @Test
    void recoverySettlesReservedPendingTradeAndPublishesDelivery() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        economy.failSettle = true;
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(UUID.randomUUID(), item, 10, 5);
        service.createBuyOrder(buyer, item, 10, 5);
        service.pumpMatching(WorkBudget.operations(16), 8);
        assertEquals(1, db.query(c -> trades.findAll(c)).stream()
                .filter(t -> t.state() == TradeState.PENDING).count());

        economy.failSettle = false;
        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries,
                economy, service);
        RecoveryService.ScanReport report = recovery.scan();

        assertEquals(1, report.fillsFinished());
        assertEquals(TradeState.SETTLED, db.query(c -> trades.findAll(c)).get(0).state());
        assertEquals(1, db.query(c ->
                deliveries.listByState(c, DeliveryState.CLAIMABLE)).size());
    }

    @Test
    void recoveryFinalizesCapturedPendingTradeWithoutPayingTwice() {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        economy.captureButReportFailureOnce = true;
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(seller, item, 10, 5);
        service.createBuyOrder(buyer, item, 10, 5);
        long sellerAfterCapture = economy.getBalance(seller);
        assertTrue(sellerAfterCapture > 0);

        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries,
                economy, service);
        assertEquals(1, recovery.scan().fillsFinished());
        assertEquals(sellerAfterCapture, economy.getBalance(seller),
                "ALREADY_SETTLED recovery must not credit seller twice");
        assertEquals(TradeState.SETTLED, db.query(c -> trades.findAll(c)).get(0).state());
    }

    @Test
    void newSellConsumesMultipleBuysInBestPriceOrder() {
        UUID buyerHigh = UUID.randomUUID();
        UUID buyerLow = UUID.randomUUID();
        economy.balances.put(buyerHigh, 10_000L);
        economy.balances.put(buyerLow, 10_000L);
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createBuyOrder(buyerLow, item, 34, 50);
        service.createBuyOrder(buyerHigh, item, 35, 50);

        AuctionService.Outcome sell = service.createSellOrder(UUID.randomUUID(), item, 30, 100);
        assertTrue(sell.isSuccess());
        List<com.valorcraft.vauction.domain.trade.Trade> fills = new ArrayList<>(sell.trades());
        Order current = db.query(c -> orders.findById(c, sell.order().orderId())).orElseThrow();
        while (current.isActive()) {
            fills.addAll(service.pumpMatching(WorkBudget.operations(16), 8).trades().stream()
                    .filter(t -> t.sellOrderId().equals(sell.order().orderId())).toList());
            current = db.query(c -> orders.findById(c, sell.order().orderId())).orElseThrow();
        }
        assertEquals(2, fills.size());
        assertEquals(List.of(35L, 34L), fills.stream()
                .map(t -> t.executionPrice()).toList());
        assertEquals(100, fills.stream().mapToInt(t -> t.quantity()).sum());
        assertEquals(OrderStatus.FILLED, current.status());
    }

    @Test
    void selfTradeIsSkipped() {
        UUID player = UUID.randomUUID();
        economy.balances.put(player, 1_000L);
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(player, item, 10, 5);
        AuctionService.Outcome buy = service.createBuyOrder(player, item, 10, 5);

        assertTrue(buy.isSuccess());
        assertTrue(buy.trades().isEmpty());
        assertEquals(2, service.playerOrders(player).size());
    }

    @Test
    void overflowingBuyTotalIsRejectedBeforeEscrow() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, Long.MAX_VALUE);
        AuctionService.Outcome outcome = service.createBuyOrder(buyer,
                new ItemStack(Items.COPPER_INGOT, 1), Long.MAX_VALUE, 2);
        assertEquals(AuctionService.Result.INVALID_PRICE, outcome.status());
        assertTrue(economy.escrows.isEmpty());
    }

    @Test
    void recoveryCompletesDurableBuyIntentThatCrashedBeforeReserve() throws Exception {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        ItemStackCodec codec = new ItemStackCodec(262_144, 2_097_152);
        ItemStack unit = new ItemStack(Items.COPPER_INGOT, 1);
        String key = new ExactItemMarketKeyStrategy(codec).keyOf(unit);
        UUID orderId = UUID.randomUUID();
        String ref = "vauction:buy:" + orderId + ":0";
        Order intent = Order.newOrder(buyer, com.valorcraft.vauction.domain.order.OrderSide.BUY,
                        key, codec.encode(unit), 20, 10, 1)
                .orderId(orderId).escrowReference(ref).build();
        db.inTransaction(c -> {
            orders.insert(c, intent);
            return null;
        });

        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries,
                economy, service);
        assertEquals(1, recovery.scan().escrowsRestored());
        assertEquals(200L, economy.reservedAmount(ref));
        assertEquals(OrderStatus.ACTIVE,
                db.query(c -> orders.findById(c, orderId)).orElseThrow().status());
    }

    @Test
    void failedInitialReserveLeavesNoTradableBuy() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        economy.failReserveAfterCalls = 0;
        AuctionService.Outcome outcome = service.createBuyOrder(buyer,
                new ItemStack(Items.COPPER_INGOT, 1), 10, 10);

        assertEquals(AuctionService.Result.INSUFFICIENT_FUNDS, outcome.status());
        assertTrue(service.playerOrders(buyer).isEmpty());
        assertTrue(db.query(c -> orders.listActive(c, 10)).isEmpty());
    }

    @Test
    void transientInitialReserveLeavesDurableNonTradableIntentForRecovery() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        economy.transientReserveFailures = 1;
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);

        AuctionService.Outcome outcome = service.createBuyOrder(buyer, item, 10, 10);
        assertEquals(AuctionService.Result.ACCEPTED_PENDING, outcome.status());
        assertTrue(outcome.isSuccess());
        assertEquals(outcome.order().orderId(),
                db.query(c -> orders.listProcessing(c, 10)).get(0).orderId());
        Order pending = db.query(c -> orders.listProcessing(c, 10)).get(0);
        assertEquals(OrderProcessingState.RESERVE, pending.processingState());
        assertTrue(db.query(c -> orders.bestBuys(c, pending.marketKey(), 1, 10)).isEmpty());

        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries, economy, service);
        assertEquals(1, recovery.scan().escrowsRestored());
        Order active = db.query(c -> orders.findById(c, pending.orderId())).orElseThrow();
        assertEquals(OrderProcessingState.NONE, active.processingState());
        assertEquals(OrderStatus.ACTIVE, active.status());
    }

    @Test
    void newerSellKeepsContinuationWhileOlderCrossingBuyIsReserveLocked() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        economy.transientReserveFailures = 1;
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        AuctionService.Outcome buy = service.createBuyOrder(buyer, item, 35, 1);
        assertEquals(AuctionService.Result.ACCEPTED_PENDING, buy.status());

        AuctionService.Outcome sell = service.createSellOrder(UUID.randomUUID(), item, 30, 1);
        service.pumpMatching(WorkBudget.operations(1), 1);
        MatchWorkRepository.MatchWork deferred = db.query(c ->
                new MatchWorkRepository().findByOrderId(c, sell.order().orderId())).orElseThrow();
        assertTrue(deferred.attemptCount() > 0);

        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries, economy, service);
        assertEquals(1, recovery.scan().escrowsRestored());
        service.pumpMatching(WorkBudget.operations(1), 1); // remove older BUY's own no-maker work
        readyMatchWork(sell.order().orderId());
        AuctionService.MatchingReport matched = service.pumpMatching(WorkBudget.operations(4), 1);

        assertEquals(1, matched.trades().size());
        assertEquals(35L, matched.trades().get(0).executionPrice());
        assertEquals(com.valorcraft.vauction.domain.order.OrderSide.BUY,
                matched.trades().get(0).makerSide());
    }

    @Test
    void newerSellKeepsContinuationWhileOlderCrossingBuyIsFillLocked() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        AuctionService.Outcome buy = service.createBuyOrder(buyer, item, 35, 2);
        service.pumpMatching(WorkBudget.operations(2), 1); // remove initial no-maker work

        economy.transientSettleFailures = 1;
        service.createSellOrder(UUID.randomUUID(), item, 30, 1);
        service.pumpMatching(WorkBudget.operations(2), 1);
        assertEquals(OrderProcessingState.FILL, db.query(c ->
                orders.findById(c, buy.order().orderId())).orElseThrow().processingState());

        AuctionService.Outcome secondSell = service.createSellOrder(UUID.randomUUID(), item, 30, 1);
        service.pumpMatching(WorkBudget.operations(1), 1);
        assertTrue(db.query(c -> new MatchWorkRepository()
                .findByOrderId(c, secondSell.order().orderId())).isPresent());

        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries, economy, service);
        assertEquals(1, recovery.scan().fillsFinished());
        readyMatchWork(secondSell.order().orderId());
        AuctionService.MatchingReport matched = service.pumpMatching(WorkBudget.operations(4), 1);

        assertEquals(1, matched.trades().size());
        assertEquals(secondSell.order().orderId(), matched.trades().get(0).sellOrderId());
        assertEquals(35L, matched.trades().get(0).executionPrice());
        assertEquals(2, db.query(trades::findAll).stream()
                .filter(t -> t.state() == TradeState.SETTLED).count());
    }

    @Test
    void matchWorkIsDeletedWhenNoOlderCrossingMakerExists() {
        AuctionService.Outcome sell = service.createSellOrder(UUID.randomUUID(),
                new ItemStack(Items.COPPER_INGOT, 1), 30, 1);
        service.pumpMatching(WorkBudget.operations(2), 1);
        assertTrue(db.query(c -> new MatchWorkRepository()
                .findByOrderId(c, sell.order().orderId())).isEmpty());
    }

    @Test
    void largeCrossIsBoundedPerPumpAndEventuallyCompletesFromDurableQueue() {
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        for (int i = 0; i < 500; i++) {
            assertTrue(service.createSellOrder(UUID.randomUUID(), item, 32, 1).isSuccess());
        }
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 20_000L);

        AuctionService.Outcome placed = service.createBuyOrder(buyer, item, 35, 500);
        assertTrue(placed.trades().size() <= AuctionWorkLimits.MAX_MATCH_FILLS_PER_PUMP);

        // Simulate restart between batches: continuation lives in SQLite, not in service memory.
        ItemStackCodec codec = new ItemStackCodec(262_144, 2_097_152);
        service = new AuctionService(db, orders, trades, new OperationRepository(), deliveries,
                codec, new ExactItemMarketKeyStrategy(codec), economy, inventory,
                AuctionSettings.defaults());
        int pumps = 1;
        while (db.query(c -> orders.findById(c, placed.order().orderId())).orElseThrow().isActive()) {
            AuctionService.MatchingReport report = service.pumpMatching(
                    WorkBudget.operations(AuctionWorkLimits.MAX_MATCH_OPERATIONS_PER_PUMP),
                    AuctionWorkLimits.MAX_MATCH_FILLS_PER_PUMP);
            assertTrue(report.trades().size() <= AuctionWorkLimits.MAX_MATCH_FILLS_PER_PUMP);
            assertTrue(report.operationsAttempted() <= AuctionWorkLimits.MAX_MATCH_OPERATIONS_PER_PUMP);
            assertTrue(pumps++ < 100, "durable matching queue did not converge");
        }
        Order filled = db.query(c -> orders.findById(c, placed.order().orderId())).orElseThrow();
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(500, filled.filledQuantity());
        assertEquals(500, db.query(trades::findAll).size());
        assertTrue(db.query(trades::findAll).stream().allMatch(t -> t.executionPrice() == 32L));
        assertEquals(500, db.query(c -> deliveries.listByState(c, DeliveryState.CLAIMABLE))
                .stream().mapToInt(d -> d.item().quantity()).sum());
        assertEquals(16_000L, economy.balances.entrySet().stream()
                .filter(e -> !e.getKey().equals(buyer) && !e.getKey().equals(economy.treasury))
                .mapToLong(Map.Entry::getValue).sum());
        assertEquals(4_000L, economy.getBalance(buyer));
        assertTrue(economy.escrows.values().stream()
                .allMatch(e -> e.state() == EconomyGateway.HoldingState.CAPTURED));
        assertFalse(db.query(new com.valorcraft.vauction.persistence.MatchWorkRepository()::hasAny));
    }

    @Test
    void expiryBurstUsesHardBudgetAndReportsContinuation() {
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        for (int i = 0; i < 1_000; i++) {
            service.createSellOrder(UUID.randomUUID(), item, 10, 1);
        }
        AuctionService.ExpiryReport first = service.expireSlice(Long.MAX_VALUE,
                WorkBudget.operations(100));
        assertEquals(AuctionWorkLimits.MAX_EXPIRY_OPERATIONS, first.operationsAttempted());
        assertEquals(AuctionWorkLimits.MAX_EXPIRY_OPERATIONS, first.completed());
        assertTrue(first.backlogRemaining());
        int completed = first.completed();
        int slices = 1;
        while (!db.query(c -> orders.listActive(c, 1)).isEmpty()) {
            AuctionService.ExpiryReport next = service.expireSlice(Long.MAX_VALUE,
                    WorkBudget.operations(100));
            assertTrue(next.operationsAttempted() <= AuctionWorkLimits.MAX_EXPIRY_OPERATIONS);
            completed += next.completed();
            assertTrue(slices++ < 130, "expiry continuation did not converge");
        }
        assertEquals(1_000, completed);
    }

    @Test
    void runtimeRecoveryIsBoundedAndDoesNotRunDeepActiveBuyAudit() {
        economy.transientReserveFailures = 20;
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        for (int i = 0; i < 20; i++) {
            UUID buyer = UUID.randomUUID();
            economy.balances.put(buyer, 10_000L);
            assertEquals(AuctionService.Result.ACCEPTED_PENDING,
                    service.createBuyOrder(buyer, item, 10, 1).status());
        }
        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries, economy, service);
        RecoveryService.ScanReport report = recovery.runtimeSlice(WorkBudget.operations(100));
        assertEquals(AuctionWorkLimits.MAX_RUNTIME_RECOVERY_OPERATIONS,
                report.operationsAttempted());
        assertTrue(report.backlogRemaining());
        assertEquals(12, db.query(c -> orders.listProcessing(c, 100)).size());
    }

    @Test
    void runtimeRecoveryDoesNotAuditTenThousandHealthyActiveBuys() {
        UUID owner = UUID.randomUUID();
        db.inTransaction(c -> {
            String sql = "WITH RECURSIVE seq(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM seq "
                    + "WHERE x<10000) INSERT INTO auction_orders (order_id,owner_uuid,side,status,"
                    + "market_key,item_blob,item_codec_version,item_hash,item_registry_id,"
                    + "item_display_name,item_search_name,item_snapshot_qty,price_per_unit,"
                    + "original_quantity,remaining_quantity,filled_quantity,escrow_reference,"
                    + "ref_epoch,created_at,updated_at,version,processing_state) SELECT "
                    + "printf('00000000-0000-0000-0000-%012d',x),?,'BUY','ACTIVE','load:key',"
                    + "X'00','test','hash','minecraft:stone','Stone','stone',1,10,1,1,0,"
                    + "('load-ref-' || x),0,x,x,0,'NONE' FROM seq";
            try (var ps = c.prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                ps.executeUpdate();
            }
            return null;
        });

        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries, economy, service);
        RecoveryService.ScanReport report = recovery.runtimeSlice(WorkBudget.operations(100));
        assertEquals(0, report.operationsAttempted());
        assertEquals(0, economy.findCalls);
    }

    @Test
    void oneHundredPendingTradesRecoverInBoundedSlices() {
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        economy.transientSettleFailures = 100;
        for (int i = 0; i < 100; i++) {
            service.createSellOrder(UUID.randomUUID(), item, 10, 1);
            UUID buyer = UUID.randomUUID();
            economy.balances.put(buyer, 100L);
            service.createBuyOrder(buyer, item, 10, 1);
            service.pumpMatching(WorkBudget.operations(16), 8);
        }
        assertEquals(100, db.query(c -> trades.findPending(c, 200)).size());

        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries, economy, service);
        int slices = 0;
        while (!db.query(c -> trades.findPending(c, 1)).isEmpty()) {
            RecoveryService.ScanReport report = recovery.runtimeSlice(WorkBudget.operations(100));
            assertTrue(report.operationsAttempted()
                    <= AuctionWorkLimits.MAX_RUNTIME_RECOVERY_OPERATIONS);
            assertTrue(slices++ < 30, "recovery continuation did not converge");
        }
        assertEquals(100, db.query(trades::findAll).stream()
                .filter(t -> t.state() == TradeState.SETTLED).count());
    }

    @Test
    void transientRolloverFailureStaysRecoverableInsteadOfManualReview() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        economy.transientSettleFailures = 1;
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(UUID.randomUUID(), item, 10, 5);
        AuctionService.Outcome buy = service.createBuyOrder(buyer, item, 10, 10);
        Order pending = db.query(c -> orders.findById(c, buy.order().orderId())).orElseThrow();

        assertEquals(OrderStatus.ACTIVE, pending.status());
        assertEquals(OrderProcessingState.FILL, pending.processingState());
        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries, economy, service);
        assertEquals(1, recovery.scan().fillsFinished());
        Order recovered = db.query(c -> orders.findById(c, pending.orderId())).orElseThrow();
        assertEquals(OrderStatus.ACTIVE, recovered.status());
        assertEquals(OrderProcessingState.NONE, recovered.processingState());
    }

    @Test
    void sellSnapshotIsCanonicalUnitWhileQuantityStaysOnOrder() {
        ItemStack stack = new ItemStack(Items.COPPER_INGOT, 32);
        AuctionService.Outcome outcome = service.createSellOrder(UUID.randomUUID(), stack, 7, 32);

        assertTrue(outcome.isSuccess());
        Order stored = db.query(c -> orders.findById(c, outcome.order().orderId())).orElseThrow();
        assertEquals(1, stored.item().quantity());
        assertEquals(32, stored.originalQuantity());
        assertEquals(32, stored.remainingQuantity());
    }

    @Test
    void pendingTradeDoesNotAffectLastTradePrice() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        economy.failSettle = true;
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(UUID.randomUUID(), item, 10, 1);
        service.createBuyOrder(buyer, item, 10, 1);

        assertEquals(0L, service.lastTradePrice(item));
        economy.failSettle = false;
        assertEquals(1, new RecoveryService(db, orders, trades, deliveries, economy, service)
                .scan().fillsFinished());
        assertEquals(10L, service.lastTradePrice(item));
    }

    @Test
    void recoveryFinalizesCapturedOldAndReservedNextEpoch() {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        economy.captureButReportFailureOnce = true;
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        service.createSellOrder(seller, item, 10, 5);
        AuctionService.Outcome created = service.createBuyOrder(buyer, item, 10, 10);
        UUID buyId = created.order().orderId();
        Order pending = db.query(c -> orders.findById(c, buyId)).orElseThrow();
        long paid = economy.getBalance(seller);

        assertEquals(OrderProcessingState.FILL, pending.processingState());
        assertEquals(50L, economy.reservedAmount("vauction:buy:" + buyId + ":1"));
        RecoveryService recovery = new RecoveryService(db, orders, trades, deliveries, economy, service);
        assertEquals(1, recovery.scan().fillsFinished());

        Order active = db.query(c -> orders.findById(c, buyId)).orElseThrow();
        assertEquals(OrderStatus.ACTIVE, active.status());
        assertEquals(OrderProcessingState.NONE, active.processingState());
        assertEquals(1, active.refEpoch());
        assertEquals(paid, economy.getBalance(seller));
    }

    @Test
    void failedCancelReleaseRemainsDurableAndRecoveryFinishesIt() {
        UUID buyer = UUID.randomUUID();
        economy.balances.put(buyer, 1_000L);
        ItemStack item = new ItemStack(Items.COPPER_INGOT, 1);
        AuctionService.Outcome created = service.createBuyOrder(buyer, item, 10, 10);
        economy.failRelease = true;

        assertEquals(AuctionService.Result.ECONOMY_FAILED,
                service.cancel(buyer, created.order().orderId(), "test").status());
        Order pending = db.query(c -> orders.findById(c, created.order().orderId())).orElseThrow();
        assertEquals(OrderProcessingState.CANCEL, pending.processingState());

        economy.failRelease = false;
        new RecoveryService(db, orders, trades, deliveries, economy, service).scan();
        Order cancelled = db.query(c -> orders.findById(c, created.order().orderId())).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(1_000L, economy.getBalance(buyer));
    }

    @Test
    void zeroExpiryDaysMeansInfiniteLifetime() {
        AuctionSettings d = AuctionSettings.defaults();
        AuctionSettings infinite = new AuctionSettings(
                d.enabled(), d.listingDurationHours(), d.maxActiveListingsPerPlayer(),
                d.maxBuyOrdersPerPlayer(), d.listingFeeMinor(), d.commissionBps(),
                d.expiredRetentionDays(), d.historyRetentionDays(), d.allowSelfPurchase(),
                d.allowContainersWithContents(), d.blockCustomNbt(), d.allowEnchantedBooks(),
                d.maxCompressedItemBytes(), d.maxUncompressedItemBytes(), 0, 0,
                d.itemPolicyMode(), d.blockedItems(), d.blockedTags(),
                d.whitelistedItems(), d.whitelistedTags());
        ItemStackCodec codec = new ItemStackCodec(262_144, 2_097_152);
        service = new AuctionService(db, orders, trades, new OperationRepository(), deliveries,
                codec, new ExactItemMarketKeyStrategy(codec), economy, inventory, infinite);
        AuctionService.Outcome sell = service.createSellOrder(UUID.randomUUID(),
                new ItemStack(Items.COPPER_INGOT, 1), 10, 1);

        assertEquals(0, service.expirePass(Long.MAX_VALUE));
        assertEquals(OrderStatus.ACTIVE,
                db.query(c -> orders.findById(c, sell.order().orderId())).orElseThrow().status());
    }

    private void readyMatchWork(UUID orderId) {
        db.inTransaction(c -> {
            MatchWorkRepository repository = new MatchWorkRepository();
            MatchWorkRepository.MatchWork work = repository.findByOrderId(c, orderId).orElseThrow();
            repository.readyNow(c, work.workId(), 0L);
            return null;
        });
    }

    private static final class FakeInventory implements InventoryOps {
        int given;
        int available = Integer.MAX_VALUE;
        int takeCalls;
        int taken;
        ItemStack lastUnit = ItemStack.EMPTY;

        @Override
        public boolean tryTake(UUID playerId, ItemStack unit, int quantity) {
            takeCalls++;
            if (quantity > available) return false;
            available -= quantity;
            taken += quantity;
            lastUnit = unit.copy();
            return true;
        }

        @Override
        public int availableCount(UUID playerId, ItemStack unit) {
            return available;
        }

        @Override
        public ItemStack give(UUID playerId, ItemStack stack) {
            given += stack.getCount();
            return ItemStack.EMPTY;
        }
    }

    private static final class FakeEconomy implements EconomyGateway {
        private record Escrow(UUID owner, long amount, HoldingState state, List<Credit> credits) {}

        final Map<UUID, Long> balances = new HashMap<>();
        final Map<String, Escrow> escrows = new HashMap<>();
        final UUID treasury = UUID.randomUUID();
        int reserveCalls;
        int failReserveAfterCalls = Integer.MAX_VALUE;
        boolean failSettle;
        boolean failRelease;
        int transientReserveFailures;
        int transientSettleFailures;
        int findCalls;
        boolean captureButReportFailureOnce;

        long reservedAmount(String ref) {
            Escrow e = escrows.get(ref);
            return e != null && e.state == HoldingState.RESERVED ? e.amount : 0L;
        }

        @Override public boolean isAvailable() { return true; }
        @Override public long getBalance(UUID playerId) { return balances.getOrDefault(playerId, 0L); }
        @Override public boolean has(UUID playerId, long amount) { return getBalance(playerId) >= amount; }
        @Override public boolean withdraw(UUID playerId, long amount, String reason, String key) { return false; }
        @Override public boolean deposit(UUID playerId, long amount, String reason, String key) { return false; }

        @Override
        public ReserveResult reserve(UUID ownerId, long amount, String referenceId,
                                     String reason, String idempotencyKey) {
            if (transientReserveFailures > 0) {
                transientReserveFailures--;
                return new ReserveResult(ReserveStatus.TRANSIENT_FAILURE, amount, referenceId);
            }
            Escrow existing = escrows.get(referenceId);
            if (existing != null) {
                return existing.owner.equals(ownerId) && existing.amount == amount
                        && existing.state == HoldingState.RESERVED
                        ? new ReserveResult(ReserveStatus.ALREADY_RESERVED, amount, referenceId)
                        : new ReserveResult(ReserveStatus.CONFLICT, amount, referenceId);
            }
            reserveCalls++;
            if (reserveCalls > failReserveAfterCalls) {
                return new ReserveResult(ReserveStatus.INSUFFICIENT_FUNDS, amount, referenceId);
            }
            if (!has(ownerId, amount)) {
                return new ReserveResult(ReserveStatus.INSUFFICIENT_FUNDS, amount, referenceId);
            }
            balances.put(ownerId, getBalance(ownerId) - amount);
            escrows.put(referenceId, new Escrow(ownerId, amount, HoldingState.RESERVED, List.of()));
            return new ReserveResult(ReserveStatus.SUCCESS, amount, referenceId);
        }

        @Override
        public SettleResult settle(String referenceId, List<Credit> credits,
                                   String reason, String idempotencyKey) {
            Escrow escrow = escrows.get(referenceId);
            if (escrow == null) {
                return new SettleResult(SettleStatus.NOT_FOUND, 0, referenceId);
            }
            if (escrow.state == HoldingState.CAPTURED) {
                return new SettleResult(SettleStatus.ALREADY_SETTLED, escrow.amount, referenceId);
            }
            long total = credits.stream().mapToLong(Credit::amount).sum();
            if (escrow.state != HoldingState.RESERVED || total != escrow.amount) {
                return new SettleResult(SettleStatus.CONFLICT, escrow.amount, referenceId);
            }
            if (failSettle) {
                return new SettleResult(SettleStatus.FAILED, escrow.amount, referenceId);
            }
            List<Credit> copy = new ArrayList<>(credits);
            for (Credit credit : copy) {
                balances.merge(credit.recipientId(), credit.amount(), Long::sum);
            }
            escrows.put(referenceId,
                    new Escrow(escrow.owner, escrow.amount, HoldingState.CAPTURED, copy));
            if (captureButReportFailureOnce) {
                captureButReportFailureOnce = false;
                return new SettleResult(SettleStatus.FAILED, escrow.amount, referenceId);
            }
            return new SettleResult(SettleStatus.SUCCESS, escrow.amount, referenceId);
        }

        @Override
        public synchronized SettleResult settleAndRollover(String oldReferenceId, List<Credit> credits,
                                                            String nextReferenceId, long remainderAmount,
                                                            String reason, String idempotencyKey) {
            Escrow old = escrows.get(oldReferenceId);
            if (transientSettleFailures > 0) {
                transientSettleFailures--;
                return new SettleResult(SettleStatus.TRANSIENT_FAILURE,
                        old == null ? 0 : old.amount, oldReferenceId);
            }
            if (old == null) {
                return new SettleResult(SettleStatus.NOT_FOUND, 0, oldReferenceId);
            }
            if (old.state == HoldingState.CAPTURED) {
                if (remainderAmount > 0) {
                    Escrow next = escrows.get(nextReferenceId);
                    if (next == null || next.state != HoldingState.RESERVED
                            || next.amount != remainderAmount || !next.owner.equals(old.owner)) {
                        return new SettleResult(SettleStatus.CONFLICT, old.amount, oldReferenceId);
                    }
                }
                return new SettleResult(SettleStatus.ALREADY_SETTLED, old.amount, oldReferenceId);
            }
            long total = credits.stream().mapToLong(Credit::amount).sum();
            if (old.state != HoldingState.RESERVED || total + remainderAmount != old.amount
                    || (remainderAmount > 0) != (nextReferenceId != null && !nextReferenceId.isBlank())
                    || (nextReferenceId != null && escrows.containsKey(nextReferenceId))) {
                return new SettleResult(SettleStatus.CONFLICT, old.amount, oldReferenceId);
            }
            if (failSettle) {
                return new SettleResult(SettleStatus.FAILED, old.amount, oldReferenceId);
            }
            for (Credit credit : credits) {
                balances.merge(credit.recipientId(), credit.amount(), Long::sum);
            }
            escrows.put(oldReferenceId,
                    new Escrow(old.owner, old.amount, HoldingState.CAPTURED, List.copyOf(credits)));
            if (remainderAmount > 0) {
                escrows.put(nextReferenceId,
                        new Escrow(old.owner, remainderAmount, HoldingState.RESERVED, List.of()));
            }
            if (captureButReportFailureOnce) {
                captureButReportFailureOnce = false;
                return new SettleResult(SettleStatus.FAILED, old.amount, oldReferenceId);
            }
            return new SettleResult(SettleStatus.SUCCESS, old.amount, oldReferenceId);
        }

        @Override
        public ReleaseResult release(String referenceId, String reason, String idempotencyKey) {
            if (failRelease) return new ReleaseResult(ReleaseStatus.FAILED, referenceId);
            Escrow escrow = escrows.get(referenceId);
            if (escrow == null) return new ReleaseResult(ReleaseStatus.NOT_FOUND, referenceId);
            if (escrow.state == HoldingState.RELEASED) {
                return new ReleaseResult(ReleaseStatus.ALREADY_RELEASED, referenceId);
            }
            if (escrow.state != HoldingState.RESERVED) {
                return new ReleaseResult(ReleaseStatus.CONFLICT, referenceId);
            }
            balances.merge(escrow.owner, escrow.amount, Long::sum);
            escrows.put(referenceId,
                    new Escrow(escrow.owner, escrow.amount, HoldingState.RELEASED, List.of()));
            return new ReleaseResult(ReleaseStatus.SUCCESS, referenceId);
        }

        @Override
        public LookupResult find(String referenceId) {
            findCalls++;
            Escrow escrow = escrows.get(referenceId);
            return escrow == null ? LookupResult.notFound()
                    : new LookupResult(LookupStatus.FOUND,
                    new Holding(escrow.owner, escrow.amount, escrow.state, escrow.credits));
        }

        @Override public UUID treasury() { return treasury; }
    }
}

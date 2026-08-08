package com.valorcraft.vauction.application;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderStatus;
import com.valorcraft.vauction.domain.trade.TradeState;
import com.valorcraft.vauction.economy.EconomyGateway;
import com.valorcraft.vauction.item.ExactItemMarketKeyStrategy;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
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
    void relockFailureFinalizesPaidFillButQuarantinesUnbackedRemainder() {
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
        assertEquals(OrderStatus.MANUAL_REVIEW, buy.status());
        assertEquals(5, buy.remainingQuantity());
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
        assertEquals(2, sell.trades().size());
        assertEquals(List.of(35L, 34L), sell.trades().stream()
                .map(t -> t.executionPrice()).toList());
        assertEquals(100, sell.filledQuantity());
        assertEquals(OrderStatus.FILLED, sell.order().status());
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

    private static final class FakeInventory implements InventoryOps {
        int given;

        @Override
        public boolean tryTake(UUID playerId, ItemStack unit, int quantity) {
            return true;
        }

        @Override
        public int availableCount(UUID playerId, ItemStack unit) {
            return Integer.MAX_VALUE;
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
        public ReleaseResult release(String referenceId, String reason, String idempotencyKey) {
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
            Escrow escrow = escrows.get(referenceId);
            return escrow == null ? LookupResult.notFound()
                    : new LookupResult(LookupStatus.FOUND,
                    new Holding(escrow.owner, escrow.amount, escrow.state, escrow.credits));
        }

        @Override public UUID treasury() { return treasury; }
    }
}

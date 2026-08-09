package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.domain.listing.AuctionListing;
import com.valorcraft.vauction.domain.listing.ListingStatus;
import com.valorcraft.vauction.domain.operation.AuctionOperation;
import com.valorcraft.vauction.domain.operation.OperationPhase;
import com.valorcraft.vauction.domain.operation.OperationStatus;
import com.valorcraft.vauction.domain.operation.OperationType;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderProcessingState;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderStatus;
import com.valorcraft.vauction.domain.sale.AuctionSale;
import com.valorcraft.vauction.item.ItemSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционные тесты на in-memory SQLite: миграции, CAS (optimistic lock),
 * уникальные ключи. Никакого Minecraft — чистая persistence-логика.
 */
class DatabaseTest {

    @TempDir
    Path tempDir;

    private DatabaseManager db;

    @BeforeEach
    void openDatabase() {
        db = DatabaseManager.openInMemory();
        db.initialize();
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    private static ItemSnapshot item(String registryId) {
        return new ItemSnapshot(
                new byte[] {31, -117, 8, 0, 0, 0, 0, 0, 0, 0, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3},
                "forge_itemstack_nbt_v1",
                "abc" + registryId.replace(":", "") + "def",
                registryId, "Предмет " + registryId, registryId, 1);
    }

    private static AuctionListing listing(long now, long expiresAt) {
        return AuctionListing.newListing(UUID.randomUUID(), item("minecraft:diamond"), 500)
                .fee(1L).commissionBps(500).times(now, expiresAt).build();
    }

    /* ------------------------------ schema/migrations ------------------------------ */

    @Test
    void migrationCreatesAllTables() {
        Set<String> tables = db.query(this::readTables);
        assertEquals(Set.of("auction_listings", "auction_buy_orders", "auction_deliveries",
                "auction_sales", "auction_operation_log", "auction_orders", "auction_trades",
                "auction_order_acceptance", "auction_match_queue", "schema_version"), tables);
        assertTrue(db.schemaVersion() >= 1, "schema version must be >= 1");
    }

    @Test
    void reinitializeIsIdempotent() {
        db.initialize();
        db.initialize();
        assertTrue(db.schemaVersion() >= 1);
    }

    @Test
    void guiMarketReadIsBoundedAcrossOneHundredMarkets() {
        OrderRepository orderRepository = new OrderRepository();
        db.inTransaction(c -> {
            for (int i = 0; i < 100; i++) {
                Order order = Order.newOrder(UUID.randomUUID(), OrderSide.SELL, "market:" + i,
                        item("minecraft:copper_ingot"), i + 1L, 1, i + 1L).build();
                orderRepository.insert(c, order);
            }
            return null;
        });
        MarketReadRepository read = new MarketReadRepository();
        assertEquals(29, db.query(c -> read.page(c, "", 0, 0, 29)).size(),
                "GUI fetches only page size plus one continuation row");
        assertEquals(29, db.query(c -> read.page(c, "", 0, 28, 29)).size());
        assertEquals(16, db.query(c -> read.page(c, "", 0, 84, 29)).size());
        assertEquals(29, db.query(c -> read.page(c, "copper", 0, 0, 29)).size(),
                "normalized search is bounded and does not read trade history");
        for (int player = 0; player < 30; player++) {
            assertTrue(db.query(c -> read.page(c, "", 0, 0, 29)).size() <= 29,
                    "simultaneous opens retain a fixed SQL/result budget");
        }
    }

    @Test
    void guiPageQueriesUseV006Indexes() {
        assertPlanUses("SELECT order_id FROM auction_orders WHERE owner_uuid='x' "
                        + "ORDER BY created_at DESC, order_id DESC LIMIT 29 OFFSET 0",
                "idx_orders_owner_page");
        assertPlanUses("SELECT delivery_id FROM auction_deliveries "
                        + "WHERE player_uuid='x' AND state='CLAIMABLE' "
                        + "ORDER BY created_at DESC, delivery_id DESC LIMIT 29 OFFSET 0",
                "idx_deliveries_player_state_page");
        assertPlanUses("SELECT market_key, MAX(settled_at) FROM auction_trades "
                        + "WHERE state='SETTLED' AND settled_at>=0 GROUP BY market_key",
                "idx_trades_settled_market");
        assertPlanUses("SELECT execution_price FROM auction_trades WHERE market_key='x' "
                        + "AND state='SETTLED' ORDER BY settled_at DESC, trade_id DESC LIMIT 1",
                "idx_trades_market_last");
    }

    @Test
    void populatedPreV003DatabaseMigratesAndKeepsOperationPhase() {
        SqliteJdbcSource source = new SqliteJdbcSource("jdbc:sqlite::memory:");
        source.open();
        try {
            source.inTransaction(c -> {
                applyResource(c, "vauction/migrations/V001__initial_schema.sql");
                applyResource(c, "vauction/migrations/V002__buy_orders.sql");
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, "
                            + "description VARCHAR(255) NOT NULL, checksum VARCHAR(64) NOT NULL, "
                            + "applied_at BIGINT NOT NULL)");
                    st.execute("INSERT INTO schema_version VALUES (1,'initial','legacy',1)");
                    st.execute("INSERT INTO schema_version VALUES (2,'buy','legacy',2)");
                    st.execute("INSERT INTO auction_operation_log "
                            + "(operation_id, operation_type, phase, status, idempotency_key, "
                            + "attempt_count, created_at, updated_at) VALUES "
                            + "('legacy-op','CREATE_LISTING','ITEM_LOCK','RUNNING','legacy-key',0,1,1)");
                }
                return null;
            });

            MigrationRunner.Result result = source.query(MigrationRunner::run);
            assertEquals(6, result.schemaVersion());
            String phase = source.query(c -> {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT phase FROM auction_operation_log WHERE operation_id='legacy-op'")) {
                    assertTrue(rs.next());
                    return rs.getString(1);
                }
            });
            assertEquals("ITEM_LOCK", phase);
        } finally {
            source.close();
        }
    }

    @Test
    void populatedV004UpgradesThroughV006AndSeedsDurableMatchingQueue() {
        SqliteJdbcSource source = new SqliteJdbcSource("jdbc:sqlite::memory:");
        source.open();
        try {
            Order existing = order(OrderSide.SELL, 32, 5, 123L);
            source.inTransaction(c -> {
                applyResource(c, "vauction/migrations/V001__initial_schema.sql");
                applyResource(c, "vauction/migrations/V002__buy_orders.sql");
                applyResource(c, "vauction/migrations/V003__unified_market.sql");
                applyResource(c, "vauction/migrations/V004__order_processing_state.sql");
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, "
                            + "description VARCHAR(255) NOT NULL, checksum VARCHAR(64) NOT NULL, "
                            + "applied_at BIGINT NOT NULL)");
                    for (int version = 1; version <= 4; version++) {
                        st.execute("INSERT INTO schema_version VALUES (" + version
                                + ",'legacy','legacy',1)");
                    }
                }
                new OrderRepository().insert(c, existing);
                return null;
            });

            MigrationRunner.Result result = source.query(MigrationRunner::run);
            assertEquals(6, result.schemaVersion());
            assertEquals(List.of("V005__bounded_work.sql", "V006__gui_read_indexes.sql"),
                    result.appliedFiles());
            int acceptedRows = source.query(c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM auction_order_acceptance")) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            });
            assertEquals(1, acceptedRows);
            assertEquals(existing.orderId(), source.query(c ->
                    new MatchWorkRepository().pollReady(c, Long.MAX_VALUE).orElseThrow().orderId()));
        } finally {
            source.close();
        }
    }

    @Test
    void pendingRecoveryQueryStaysIndexedWithOneHundredThousandSettledTrades() {
        OrderRepository orderRepo = new OrderRepository();
        Order buy = order(OrderSide.BUY, 10, 1, 1);
        Order sell = order(OrderSide.SELL, 10, 1, 1);
        db.inTransaction(c -> {
            orderRepo.insert(c, buy);
            orderRepo.insert(c, sell);
            String bulk = "WITH RECURSIVE seq(x) AS (VALUES(1) UNION ALL "
                    + "SELECT x+1 FROM seq WHERE x<100000) "
                    + "INSERT INTO auction_trades (trade_id,market_key,buy_order_id,sell_order_id,"
                    + "maker_side,execution_price,quantity,gross_minor,commission_minor,"
                    + "seller_net_minor,buyer_uuid,seller_uuid,escrow_reference,state,created_at,"
                    + "settled_at,version) SELECT printf('settled-%06d',x),'exact:key',?,?,'SELL',"
                    + "10,1,10,0,10,?,?,('bulk-ref-' || x),'SETTLED',x,x,0 FROM seq";
            try (PreparedStatement ps = c.prepareStatement(bulk)) {
                ps.setString(1, buy.orderId().toString());
                ps.setString(2, sell.orderId().toString());
                ps.setString(3, buy.ownerUuid().toString());
                ps.setString(4, sell.ownerUuid().toString());
                ps.executeUpdate();
            }
            String pending = "INSERT INTO auction_trades (trade_id,market_key,buy_order_id,"
                    + "sell_order_id,maker_side,execution_price,quantity,gross_minor,commission_minor,"
                    + "seller_net_minor,buyer_uuid,seller_uuid,escrow_reference,state,created_at,version) "
                    + "VALUES (?,?,?,?, 'SELL',10,1,10,0,10,?,?,?,'PENDING',?,0)";
            try (PreparedStatement ps = c.prepareStatement(pending)) {
                for (int i = 0; i < 3; i++) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, "exact:key");
                    ps.setString(3, buy.orderId().toString());
                    ps.setString(4, sell.orderId().toString());
                    ps.setString(5, buy.ownerUuid().toString());
                    ps.setString(6, sell.ownerUuid().toString());
                    ps.setString(7, "pending-ref-" + i);
                    ps.setLong(8, 100_001L + i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });

        List<com.valorcraft.vauction.domain.trade.Trade> pending = db.query(c ->
                new TradeRepository().findPending(c, 32));
        assertEquals(3, pending.size());
        String plan = db.query(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "EXPLAIN QUERY PLAN SELECT trade_id FROM auction_trades "
                            + "WHERE state='PENDING' ORDER BY created_at,trade_id LIMIT 32")) {
                StringBuilder out = new StringBuilder();
                while (rs.next()) out.append(rs.getString("detail"));
                return out.toString();
            }
        });
        assertTrue(plan.contains("idx_trades_pending"), plan);
    }

    @Test
    void boundedMaintenanceQueriesUseV005Indexes() {
        assertPlanUses("SELECT work_id FROM auction_match_queue WHERE next_attempt_at<=1 "
                + "ORDER BY next_attempt_at,created_at,work_id LIMIT 1", "idx_match_queue_ready");
        assertPlanUses("SELECT o.order_id FROM auction_orders o "
                + "JOIN auction_order_acceptance maker_seq ON maker_seq.order_id=o.order_id "
                + "JOIN auction_order_acceptance incoming_seq ON incoming_seq.order_id='incoming' "
                + "WHERE o.market_key='k' AND o.side='SELL' AND o.status='ACTIVE' "
                + "AND o.processing_state='NONE' AND o.price_per_unit<=10 "
                + "AND maker_seq.sequence<incoming_seq.sequence "
                + "ORDER BY o.price_per_unit,maker_seq.sequence LIMIT 1",
                "idx_orders_match");
        assertPlanUses("SELECT order_id FROM auction_orders WHERE side='SELL' AND status='ACTIVE' "
                + "AND processing_state='NONE' AND created_at<=10 ORDER BY created_at LIMIT 9",
                "idx_orders_expiry");
        assertPlanUses("SELECT order_id FROM auction_orders WHERE processing_state<>'NONE' "
                + "ORDER BY updated_at,order_id LIMIT 32", "idx_orders_processing_cursor");
        assertPlanUses("SELECT delivery_id FROM auction_deliveries WHERE state='CLAIMING' "
                + "ORDER BY delivery_id LIMIT 32", "idx_deliveries_state");
    }

    /* ------------------------------ unified order book ------------------------------ */

    @Test
    void equalPriceFifoUsesDurableAcceptanceSequenceOnBothSides() {
        OrderRepository repo = new OrderRepository();
        MatchWorkRepository work = new MatchWorkRepository();
        long sameTime = 123L;
        Order sellFirst = orderWith(UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1"),
                OrderSide.SELL, "fifo:sell", 32, sameTime);
        Order sellSecond = orderWith(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                OrderSide.SELL, "fifo:sell", 32, sameTime);
        Order incomingBuy = orderWith(UUID.randomUUID(), OrderSide.BUY, "fifo:sell", 35, sameTime);
        Order buyFirst = orderWith(UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff2"),
                OrderSide.BUY, "fifo:buy", 35, sameTime);
        Order buySecond = orderWith(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                OrderSide.BUY, "fifo:buy", 35, sameTime);
        Order incomingSell = orderWith(UUID.randomUUID(), OrderSide.SELL, "fifo:buy", 32, sameTime);
        db.inTransaction(c -> {
            for (Order order : List.of(sellFirst, sellSecond, incomingBuy,
                    buyFirst, buySecond, incomingSell)) {
                repo.insert(c, order);
                work.registerOrder(c, order.orderId());
            }
            return null;
        });

        Order selectedSell = db.query(c -> repo.bestCounterpart(c, "fifo:sell", OrderSide.BUY,
                35, incomingBuy.ownerUuid(), true, incomingBuy.orderId())).orElseThrow();
        Order selectedBuy = db.query(c -> repo.bestCounterpart(c, "fifo:buy", OrderSide.SELL,
                32, incomingSell.ownerUuid(), true, incomingSell.orderId())).orElseThrow();

        assertEquals(sellFirst.orderId(), selectedSell.orderId(),
                "UUID order must not override SELL acceptance FIFO");
        assertEquals(buyFirst.orderId(), selectedBuy.orderId(),
                "UUID order must not override BUY acceptance FIFO");
    }

    @Test
    void fileDatabaseCreatesMissingParentDirectory() {
        Path databasePath = tempDir.resolve("new-world").resolve("vauction").resolve("auction.db");

        try (DatabaseManager fileDb = DatabaseManager.openSqlite(databasePath)) {
            fileDb.initialize();
            assertEquals(6, fileDb.schemaVersion());
        }

        assertTrue(Files.isRegularFile(databasePath));
    }

    @Test
    void acceptanceRegistrationAndMatchEnqueueAreIdempotent() {
        Order existing = order(OrderSide.SELL, 32, 1, 1L);
        MatchWorkRepository work = new MatchWorkRepository();
        db.inTransaction(c -> {
            new OrderRepository().insert(c, existing);
            work.registerOrder(c, existing.orderId());
            work.registerOrder(c, existing.orderId());
            work.enqueue(c, existing.orderId(), existing.createdAt());
            work.enqueue(c, existing.orderId(), existing.createdAt());
            return null;
        });
        assertEquals(1, db.query(work::count));
        assertEquals(1, db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM auction_order_acceptance WHERE order_id=?")) {
                ps.setString(1, existing.orderId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).intValue());
    }

    @Test
    void orderInsertRoundTripKeepsPriceAndAllQuantities() {
        OrderRepository repo = new OrderRepository();
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Order order = Order.newOrder(owner, OrderSide.BUY, "exact:key",
                        item("minecraft:copper_ingot"), 37L, 500, 100L)
                .orderId(id).filledQuantity(125).refEpoch(4)
                .escrowReference("vauction:buy:" + id + ":4").build();

        db.inTransaction(c -> {
            repo.insert(c, order);
            return null;
        });
        Order read = db.query(c -> repo.findById(c, id)).orElseThrow();

        assertEquals(37L, read.pricePerUnit());
        assertEquals(500, read.originalQuantity());
        assertEquals(375, read.remainingQuantity());
        assertEquals(125, read.filledQuantity());
        assertEquals(4, read.refEpoch());
        assertEquals(order.escrowReference(), read.escrowReference());
        assertEquals(OrderStatus.ACTIVE, read.status());
    }

    @Test
    void orderBookUsesPriceThenFifoOnBothSides() {
        OrderRepository repo = new OrderRepository();
        Order sellOld = order(OrderSide.SELL, 30, 10, 100);
        Order sellCheap = order(OrderSide.SELL, 20, 10, 300);
        Order sellNew = order(OrderSide.SELL, 30, 10, 200);
        Order buyOld = order(OrderSide.BUY, 40, 10, 100);
        Order buyHigh = order(OrderSide.BUY, 50, 10, 300);
        Order buyNew = order(OrderSide.BUY, 40, 10, 200);
        db.inTransaction(c -> {
            for (Order o : new Order[] {sellOld, sellCheap, sellNew, buyOld, buyHigh, buyNew}) {
                repo.insert(c, o);
            }
            return null;
        });

        assertEquals(java.util.List.of(sellCheap.orderId(), sellOld.orderId(), sellNew.orderId()),
                db.query(c -> repo.bestSells(c, "exact:key", 99, 10)).stream()
                        .map(Order::orderId).toList());
        assertEquals(java.util.List.of(buyHigh.orderId(), buyOld.orderId(), buyNew.orderId()),
                db.query(c -> repo.bestBuys(c, "exact:key", 1, 10)).stream()
                        .map(Order::orderId).toList());
    }

    @Test
    void orderConsumptionIsCasProtectedAndPreservesInvariant() {
        OrderRepository repo = new OrderRepository();
        Order original = order(OrderSide.SELL, 32, 100, 100);
        db.inTransaction(c -> {
            repo.insert(c, original);
            return null;
        });

        assertEquals(Integer.valueOf(50),
                db.inTransaction(c -> repo.tryConsume(c, original, 50, 200)));
        assertNull(db.inTransaction(c -> repo.tryConsume(c, original, 50, 201)),
                "stale version must not consume the same remainder twice");
        Order half = db.query(c -> repo.findById(c, original.orderId())).orElseThrow();
        assertEquals(50, half.remainingQuantity());
        assertEquals(50, half.filledQuantity());
        assertEquals(100, half.remainingQuantity() + half.filledQuantity());
        assertEquals(Integer.valueOf(0),
                db.inTransaction(c -> repo.tryConsume(c, half, 50, 202)));
        Order filled = db.query(c -> repo.findById(c, original.orderId())).orElseThrow();
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(100, filled.filledQuantity());
    }

    @Test
    void oneBuyEpochRejectsSecondFillCancelAndExpiryRaces() {
        OrderRepository repo = new OrderRepository();
        Order buy = order(OrderSide.BUY, 10, 10, 100);
        db.inTransaction(c -> {
            repo.insert(c, buy);
            return null;
        });

        assertEquals(Integer.valueOf(5),
                db.inTransaction(c -> repo.tryConsumeBuyForFill(c, buy, 5, 200)));
        assertNull(db.inTransaction(c -> repo.tryConsumeBuyForFill(c, buy, 5, 201)),
                "the same epoch cannot have two in-flight fills");
        assertEquals(false, db.inTransaction(c -> repo.applyState(c, buy,
                        buy.withProcessingState(OrderProcessingState.CANCEL, 202))),
                "cancel must lose after fill acquired the epoch");
        assertEquals(false, db.inTransaction(c -> repo.applyState(c, buy,
                        buy.withProcessingState(OrderProcessingState.EXPIRE, 203))),
                "expiry must lose after fill acquired the epoch");

        Order locked = db.query(c -> repo.findById(c, buy.orderId())).orElseThrow();
        assertEquals(OrderProcessingState.FILL, locked.processingState());
        assertEquals(5, locked.remainingQuantity());
        assertTrue(db.query(c -> repo.bestBuys(c, "exact:key", 1, 10)).isEmpty(),
                "an in-flight epoch must not be matchable");
    }

    @Test
    void terminalIntentWinningRacePreventsFill() {
        OrderRepository repo = new OrderRepository();
        Order cancelling = order(OrderSide.BUY, 10, 10, 100);
        Order expiring = order(OrderSide.BUY, 10, 10, 101);
        db.inTransaction(c -> {
            repo.insert(c, cancelling);
            repo.insert(c, expiring);
            return null;
        });

        assertEquals(true, db.inTransaction(c -> repo.applyState(c, cancelling,
                cancelling.withProcessingState(OrderProcessingState.CANCEL, 200))));
        assertNull(db.inTransaction(c -> repo.tryConsumeBuyForFill(c, cancelling, 1, 201)));
        assertEquals(true, db.inTransaction(c -> repo.applyState(c, expiring,
                expiring.withProcessingState(OrderProcessingState.EXPIRE, 202))));
        assertNull(db.inTransaction(c -> repo.tryConsumeBuyForFill(c, expiring, 1, 203)));
    }

    @Test
    void concurrentFillFillCancelExpiryHasExactlyOneWinner() throws Exception {
        OrderRepository repo = new OrderRepository();
        Order buy = order(OrderSide.BUY, 10, 10, 100);
        db.inTransaction(c -> {
            repo.insert(c, buy);
            return null;
        });
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> attempts = List.of(
                    pool.submit(() -> { start.await(); return db.inTransaction(c ->
                            repo.tryConsumeBuyForFill(c, buy, 1, 200) != null); }),
                    pool.submit(() -> { start.await(); return db.inTransaction(c ->
                            repo.tryConsumeBuyForFill(c, buy, 1, 201) != null); }),
                    pool.submit(() -> { start.await(); return db.inTransaction(c ->
                            repo.applyState(c, buy, buy.withProcessingState(OrderProcessingState.CANCEL, 202))); }),
                    pool.submit(() -> { start.await(); return db.inTransaction(c ->
                            repo.applyState(c, buy, buy.withProcessingState(OrderProcessingState.EXPIRE, 203))); })
            );
            start.countDown();
            int winners = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) winners++;
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }

    /* ------------------------------ listings + CAS ------------------------------ */

    @Test
    void listingInsertRoundTrip() {
        long now = System.currentTimeMillis();
        long id = db.inTransaction(c -> new ListingRepository().insert(c,
                listing(now, now + 48L * 3600_000L)));

        AuctionListing read = db.query(c -> new ListingRepository().findById(c, id)).orElseThrow();
        assertEquals(ListingStatus.ACTIVE, read.status());
        assertEquals(500, read.priceMinor());
        assertEquals(1, read.listingFeeMinor());
        assertEquals(500, read.commissionBps());
        assertEquals("minecraft:diamond", read.item().registryId());
        assertEquals(0, read.version(), "новый лот должен начинаться с version=0");
        assertTrue(read.expiresAt() > read.createdAt());
    }

    @Test
    void optimisticLockAllowsSingleTransition() {
        ListingRepository repo = new ListingRepository();
        long now = System.currentTimeMillis();
        long id = db.inTransaction(c -> repo.insert(c, listing(now, now + 3600_000L)));

        AuctionListing current = db.query(c -> repo.findById(c, id)).orElseThrow();

        AuctionListing reserved = current.toReserved(UUID.randomUUID(), "res-1",
                now, now + 60_000L, now + 1000L);
        boolean first = db.inTransaction(c -> repo.applyState(c, current, reserved));
        assertTrue(first, "переход с актуальной version должен пройти");

        AuctionListing afterFirst = db.query(c -> repo.findById(c, id)).orElseThrow();
        assertEquals(1, afterFirst.version());
        assertEquals(ListingStatus.RESERVED, afterFirst.status());

        boolean stale = db.inTransaction(c -> repo.applyState(c, current, reserved));
        assertFalse(stale, "повторный переход со старой version должен упасть (CAS)");

        AuctionListing sold = afterFirst.toSold(afterFirst.buyerUuid(), now + 2000L);
        boolean second = db.inTransaction(c -> repo.applyState(c, afterFirst, sold));
        assertTrue(second);
        assertEquals(ListingStatus.SOLD,
                db.query(c -> repo.findById(c, id)).orElseThrow().status());
    }

    /* ------------------------------ deliveries ------------------------------ */

    @Test
    void deliveryDedupeKeyIsUnique() {
        DeliveryRepository repo = new DeliveryRepository();
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();

        AuctionDelivery d = AuctionDelivery.newDelivery(player, 7L, "op-1", DeliveryType.PURCHASED,
                item("minecraft:iron_ingot"), now).dedupeKey("sale:7").build();

        long id = db.inTransaction(c -> repo.insert(c, d));
        assertTrue(id > 0);

        AuctionDelivery read = db.query(c -> repo.findByDedupeKey(c, "sale:7")).orElseThrow();
        assertEquals(player, read.playerUuid());
        assertEquals(DeliveryType.PURCHASED, read.deliveryType());
        assertEquals("minecraft:iron_ingot", read.item().registryId());

        DatabaseException ex = assertThrows(DatabaseException.class,
                () -> db.inTransaction(c -> repo.insert(c, d)));
        assertTrue(ex.getMessage().contains("dedupe_key"));
    }

    @Test
    void deliveryStateTransitionsPersistWithCas() {
        DeliveryRepository repo = new DeliveryRepository();
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        long id = db.inTransaction(c -> repo.insert(c, AuctionDelivery
                .newDelivery(player, 1L, "op-1", DeliveryType.CANCELLED_RETURN, item("minecraft:dirt"), now)
                .dedupeKey("return:1").build()));

        AuctionDelivery pending = db.query(c -> repo.findById(c, id)).orElseThrow();
        boolean toClaimable = db.inTransaction(c ->
                repo.applyState(c, pending, pending.toClaimable(now + 3600_000L, "token-1")));
        assertTrue(toClaimable);

        AuctionDelivery claimable = db.query(c -> repo.findById(c, id)).orElseThrow();
        assertEquals(DeliveryState.CLAIMABLE, claimable.state());
        boolean toClaiming = db.inTransaction(c ->
                repo.applyState(c, claimable, claimable.toClaiming(now + 4000L)));
        assertTrue(toClaiming);
        assertEquals(DeliveryState.CLAIMING,
                db.query(c -> repo.findById(c, id)).orElseThrow().state());

        AuctionDelivery claiming = db.query(c -> repo.findById(c, id)).orElseThrow();
        boolean toClaimed = db.inTransaction(c ->
                repo.applyState(c, claiming, claiming.toClaimed(now + 8000L)));
        assertTrue(toClaimed);
        assertEquals(DeliveryState.CLAIMED,
                db.query(c -> repo.findById(c, id)).orElseThrow().state());
    }

    /* ------------------------------ sales ------------------------------ */

    @Test
    void saleInsertRoundTripAndListingUniqueness() {
        SaleRepository repo = new SaleRepository();
        long now = System.currentTimeMillis();
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();

        AuctionSale sale = AuctionSale.newSale(seller, buyer, 500, "esc-1", "hash-1", now)
                .listingId(9L).purchaseOperationId("op-buy-1").commissionMinor(50).sellerNetMinor(450)
                .build();

        long id = db.inTransaction(c -> repo.insert(c, sale));
        AuctionSale read = db.query(c -> repo.findById(c, id)).orElseThrow();
        assertEquals(9L, read.listingId());
        assertEquals(500, read.grossMinor());
        assertEquals(50, read.commissionMinor());
        assertEquals(450, read.sellerNetMinor());
        assertEquals("esc-1", read.escrowReference());
        assertEquals(seller, read.sellerUuid());

        AuctionSale second = AuctionSale.newSale(seller, buyer, 500, "esc-2", "hash-2", now)
                .listingId(9L).purchaseOperationId("op-buy-2").commissionMinor(50).sellerNetMinor(450)
                .build();
        DatabaseException ex = assertThrows(DatabaseException.class,
                () -> db.inTransaction(c -> repo.insert(c, second)));
        assertTrue(ex.getMessage().contains("sale"), "одна продажа на лот: " + ex.getMessage());

        assertTrue(db.query(c -> repo.findByListingId(c, 9L)).isPresent());
    }

    @Test
    void saleInvariantGrossEqualsCommissionPlusNetIsEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> AuctionSale.newSale(UUID.randomUUID(), UUID.randomUUID(), 500, "esc", "h", 1L)
                        .purchaseOperationId("op-bad")
                        .commissionMinor(50).sellerNetMinor(400).build(),
                "gross != commission + net должен падать в конструкторе/CHECK");
    }

    /* ------------------------------ operations ------------------------------ */

    @Test
    void operationIdempotencyAndRetryCas() {
        OperationRepository repo = new OperationRepository();
        long now = System.currentTimeMillis();

        AuctionOperation op = AuctionOperation.newOperation(OperationType.CREATE_LISTING,
                "create:player:hash", now).operationId("vl-1").listingId(3L)
                .actor(UUID.randomUUID()).build();

        db.inTransaction(c -> {
            repo.insert(c, op);
            return 1;
        });

        AuctionOperation stored = db.query(c -> repo.findById(c, "vl-1")).orElseThrow();
        assertEquals(0, stored.attemptCount());
        assertEquals(OperationType.CREATE_LISTING, stored.operationType());

        // первая попытка упала: toFailed увеличивает attemptCount 0 → 1
        AuctionOperation failed = stored.toFailed("temporary", now + 5000L, now + 200L);
        boolean first = db.inTransaction(c -> repo.applyRetry(c, "vl-1", 0, failed));
        assertTrue(first, "CAS по attempt_count=0 должен пройти");
        boolean staleRetry = db.inTransaction(c -> repo.applyRetry(c, "vl-1", 0, failed));
        assertFalse(staleRetry, "повтор с attempt_count=0 не должен пройти");

        // повторная попытка успешна: завершаем с текущим attempt_count=1
        AuctionOperation current = db.query(c -> repo.findById(c, "vl-1")).orElseThrow();
        assertEquals(1, current.attemptCount());
        AuctionOperation completed = current.toCompleted(now + 400L);
        boolean finalRetry = db.inTransaction(c -> repo.applyRetry(c, "vl-1", 1, completed));
        assertTrue(finalRetry, "CAS по attempt_count=1 должен пройти");

        AuctionOperation done = db.query(c -> repo.findById(c, "vl-1")).orElseThrow();
        assertEquals(OperationPhase.COMPLETE, done.phase());
        assertEquals(OperationStatus.COMPLETED, done.status());
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        OperationRepository repo = new OperationRepository();
        long now = System.currentTimeMillis();
        AuctionOperation a = AuctionOperation.newOperation(OperationType.CREATE_LISTING,
                "same-key", now).operationId("vl-a").build();
        AuctionOperation b = AuctionOperation.newOperation(OperationType.CREATE_LISTING,
                "same-key", now).operationId("vl-b").build();

        db.inTransaction(c -> {
            repo.insert(c, a);
            return 1;
        });
        assertThrows(DatabaseException.class, () -> db.inTransaction(c -> {
            repo.insert(c, b);
            return 1;
        }));
    }

    /* ------------------------------ helpers ------------------------------ */

    private void assertPlanUses(String sql, String index) {
        String plan = db.query(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
                StringBuilder out = new StringBuilder();
                while (rs.next()) out.append(rs.getString("detail")).append('\n');
                return out.toString();
            }
        });
        assertTrue(plan.contains(index), plan);
    }

    private Set<String> readTables(Connection c) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static Order order(OrderSide side, long price, int quantity, long createdAt) {
        return Order.newOrder(UUID.randomUUID(), side, "exact:key",
                item("minecraft:copper_ingot"), price, quantity, createdAt).build();
    }

    private static Order orderWith(UUID id, OrderSide side, String marketKey,
                                   long price, long createdAt) {
        return Order.newOrder(UUID.randomUUID(), side, marketKey,
                item("minecraft:copper_ingot"), price, 1, createdAt).orderId(id).build();
    }

    private static void applyResource(Connection c, String path) throws Exception {
        String sql;
        try (var in = DatabaseTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String statement : MigrationRunner.splitStatements(sql)) {
            try (Statement st = c.createStatement()) {
                st.execute(statement);
            }
        }
    }
}

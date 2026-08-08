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
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderStatus;
import com.valorcraft.vauction.domain.sale.AuctionSale;
import com.valorcraft.vauction.item.ItemSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
                "schema_version"), tables);
        assertTrue(db.schemaVersion() >= 1, "schema version must be >= 1");
    }

    @Test
    void reinitializeIsIdempotent() {
        db.initialize();
        db.initialize();
        assertTrue(db.schemaVersion() >= 1);
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
            assertEquals(3, result.schemaVersion());
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

    /* ------------------------------ unified order book ------------------------------ */

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

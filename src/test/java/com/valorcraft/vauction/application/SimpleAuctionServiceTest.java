package com.valorcraft.vauction.application;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.economy.EconomyGateway;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.ListingRepository;
import com.valorcraft.vauction.persistence.OperationRepository;
import com.valorcraft.vauction.persistence.SaleRepository;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.DetectedVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SimpleAuctionServiceTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        try { Bootstrap.bootStrap(); } catch (Throwable ignored) {}
    }
    private DatabaseManager db;
    private ListingRepository listings;
    private DeliveryRepository deliveries;
    private FakeInventory inventory;
    private FakeEconomy economy;
    private SimpleAuctionService service;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.openInMemory();
        db.initialize();
        listings = new ListingRepository();
        deliveries = new DeliveryRepository();
        inventory = new FakeInventory();
        economy = new FakeEconomy();
        service = new SimpleAuctionService(db, listings, new SaleRepository(), deliveries,
                new OperationRepository(), new ItemStackCodec(262_144, 2_097_152),
                economy, inventory, AuctionSettings.defaults());
    }

    @AfterEach void tearDown() { if (db != null) db.close(); }

    @Test
    void createsOneWholeStackListingAndCatalogueShowsIt() {
        UUID seller = UUID.randomUUID();
        inventory.put(seller, new ItemStack(Items.BREAD, 17));

        var result = service.create(seller, new ItemStack(Items.BREAD, 17), 450);

        assertTrue(result.success());
        assertEquals(0, inventory.count(seller));
        var page = service.catalogue(seller, false, null, "", 0, 36);
        assertEquals(1, page.totalItems());
        assertEquals(17, page.items().get(0).item().quantity());
        assertEquals(450, page.items().get(0).priceMinor());
    }

    @Test
    void purchaseIsSingleWinnerPaysSellerAndCreatesOneClaimableDelivery() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        inventory.put(seller, new ItemStack(Items.DIAMOND, 3));
        economy.balance.put(buyer, 1_000L);
        economy.balance.put(other, 1_000L);
        long id = service.create(seller, new ItemStack(Items.DIAMOND, 3), 500).listing().listingId();

        var bought = service.purchase(buyer, id);
        var tooLate = service.purchase(other, id);

        assertTrue(bought.success());
        assertEquals(SimpleAuctionService.Result.CHANGED, tooLate.result());
        assertEquals(500 - 12, economy.balance.getOrDefault(seller, 0L));
        assertEquals(500, economy.balance.get(buyer));
        assertEquals(DeliveryState.CLAIMABLE,
                db.query(c -> deliveries.findById(c, bought.deliveryId()).orElseThrow()).state());
        assertTrue(db.query(c -> new SaleRepository().findByListingId(c, id)).isPresent());
    }

    @Test
    void cancellingOwnListingCreatesReturnAndForeignCancelIsRejected() {
        UUID seller = UUID.randomUUID();
        inventory.put(seller, new ItemStack(Items.IRON_INGOT, 8));
        long id = service.create(seller, new ItemStack(Items.IRON_INGOT, 8), 200).listing().listingId();

        assertEquals(SimpleAuctionService.Result.NOT_YOURS,
                service.cancel(UUID.randomUUID(), id).result());
        var cancelled = service.cancel(seller, id);

        assertTrue(cancelled.success());
        assertEquals(DeliveryState.CLAIMABLE,
                db.query(c -> deliveries.findById(c, cancelled.deliveryId()).orElseThrow()).state());
        assertEquals(0, service.catalogue(seller, true, null, "", 0, 36).totalItems());
    }

    private static final class FakeInventory implements InventoryOps {
        private final Map<UUID, ItemStack> stacks = new HashMap<>();
        void put(UUID id, ItemStack stack) { stacks.put(id, stack.copy()); }
        int count(UUID id) { return stacks.getOrDefault(id, ItemStack.EMPTY).getCount(); }
        public boolean tryTake(UUID id, ItemStack unit, int quantity) {
            ItemStack stack = stacks.get(id);
            if (stack == null || !ItemStack.isSameItemSameTags(stack, unit) || stack.getCount() < quantity) return false;
            stack.shrink(quantity);
            return true;
        }
        public int availableCount(UUID id, ItemStack unit) { return count(id); }
        public ItemStack give(UUID id, ItemStack stack) { stacks.put(id, stack.copy()); return ItemStack.EMPTY; }
    }

    private static final class FakeEconomy implements EconomyGateway {
        final Map<UUID, Long> balance = new HashMap<>();
        final Map<String, Holding> holds = new HashMap<>();
        final UUID treasury = UUID.randomUUID();
        public boolean isAvailable() { return true; }
        public long getBalance(UUID id) { return balance.getOrDefault(id, 0L); }
        public boolean has(UUID id, long amount) { return getBalance(id) >= amount; }
        public boolean withdraw(UUID id, long amount, String reason, String key) { return false; }
        public boolean deposit(UUID id, long amount, String reason, String key) { return false; }
        public ReserveResult reserve(UUID owner, long amount, String ref, String reason, String key) {
            Holding existing = holds.get(ref);
            if (existing != null) return new ReserveResult(ReserveStatus.ALREADY_RESERVED, existing.amount(), ref);
            if (!has(owner, amount)) return new ReserveResult(ReserveStatus.INSUFFICIENT_FUNDS, 0, ref);
            balance.put(owner, getBalance(owner) - amount);
            holds.put(ref, new Holding(owner, amount, HoldingState.RESERVED, List.of()));
            return new ReserveResult(ReserveStatus.SUCCESS, amount, ref);
        }
        public SettleResult settle(String ref, List<Credit> credits, String reason, String key) {
            Holding hold = holds.get(ref);
            if (hold == null) return new SettleResult(SettleStatus.NOT_FOUND, 0, ref);
            if (hold.state() == HoldingState.CAPTURED) return new SettleResult(SettleStatus.ALREADY_SETTLED, hold.amount(), ref);
            for (Credit credit : credits) balance.merge(credit.recipientId(), credit.amount(), Long::sum);
            holds.put(ref, new Holding(hold.ownerId(), hold.amount(), HoldingState.CAPTURED, credits));
            return new SettleResult(SettleStatus.SUCCESS, hold.amount(), ref);
        }
        public SettleResult settleAndRollover(String oldRef, List<Credit> credits, String nextRef,
                                             long remainder, String reason, String key) {
            return settle(oldRef, credits, reason, key);
        }
        public ReleaseResult release(String ref, String reason, String key) {
            Holding h = holds.get(ref);
            if (h == null) return new ReleaseResult(ReleaseStatus.NOT_FOUND, ref);
            balance.merge(h.ownerId(), h.amount(), Long::sum);
            holds.put(ref, new Holding(h.ownerId(), h.amount(), HoldingState.RELEASED, List.of()));
            return new ReleaseResult(ReleaseStatus.SUCCESS, ref);
        }
        public LookupResult find(String ref) {
            Holding h = holds.get(ref);
            return h == null ? LookupResult.notFound() : new LookupResult(LookupStatus.FOUND, h);
        }
        public UUID treasury() { return treasury; }
    }
}

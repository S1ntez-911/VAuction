package com.valorcraft.vauction.application;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.domain.listing.AuctionListing;
import com.valorcraft.vauction.domain.listing.ListingStatus;
import com.valorcraft.vauction.domain.operation.AuctionOperation;
import com.valorcraft.vauction.domain.operation.OperationPhase;
import com.valorcraft.vauction.domain.operation.OperationType;
import com.valorcraft.vauction.domain.sale.AuctionSale;
import com.valorcraft.vauction.economy.EconomyGateway;
import com.valorcraft.vauction.item.ItemCodecException;
import com.valorcraft.vauction.item.ItemPolicy;
import com.valorcraft.vauction.item.ItemSnapshot;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.item.MarketCategoryClassifier;
import com.valorcraft.vauction.persistence.DatabaseException;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.ListingRepository;
import com.valorcraft.vauction.persistence.OperationRepository;
import com.valorcraft.vauction.persistence.SaleRepository;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fixed-price, whole-stack auction used by all player-facing commands and screens. */
public final class SimpleAuctionService {
    private static final Logger LOGGER = LogManager.getLogger("VAuction/Simple");
    private static final long RESERVATION_MILLIS = 120_000L;

    public enum Result {
        SUCCESS, NOT_FOUND, NOT_YOURS, OWN_LISTING, CHANGED, INVALID_PRICE, INVALID_ITEM,
        OVER_LIMIT, INSUFFICIENT_FUNDS, ECONOMY_FAILED, DATABASE_FAILED, ACCEPTED_PENDING
    }

    public record Outcome(Result result, String message, AuctionListing listing, Long deliveryId) {
        public boolean success() { return result == Result.SUCCESS || result == Result.ACCEPTED_PENDING; }
        static Outcome ok(String message, AuctionListing listing, Long deliveryId) {
            return new Outcome(Result.SUCCESS, message, listing, deliveryId);
        }
        static Outcome fail(Result result, String message) { return new Outcome(result, message, null, null); }
    }

    private final DatabaseManager database;
    private final ListingRepository listings;
    private final SaleRepository sales;
    private final DeliveryRepository deliveries;
    private final OperationRepository operations;
    private final ItemStackCodec codec;
    private final EconomyGateway economy;
    private final InventoryOps inventory;
    private final AuctionSettings settings;

    public SimpleAuctionService(DatabaseManager database, ListingRepository listings,
                                SaleRepository sales, DeliveryRepository deliveries,
                                OperationRepository operations, ItemStackCodec codec,
                                EconomyGateway economy, InventoryOps inventory,
                                AuctionSettings settings) {
        this.database = database;
        this.listings = listings;
        this.sales = sales;
        this.deliveries = deliveries;
        this.operations = operations;
        this.codec = codec;
        this.economy = economy;
        this.inventory = inventory;
        this.settings = settings;
    }

    public Page<AuctionListing> catalogue(UUID viewer, boolean mineOnly, String category,
                                          String search, int page, int pageSize) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, pageSize);
        UUID seller = mineOnly ? viewer : null;
        ListingRepository.ListingPage result = database.query(c -> listings.simpleActivePage(c,
                seller, category, search, safePage * safeSize, safeSize));
        int totalPages = result.total() == 0 ? 1 : (int) Math.min(Integer.MAX_VALUE,
                (result.total() + safeSize - 1L) / safeSize);
        return new Page<>(result.items(), safePage, safePage > 0, safePage + 1 < totalPages,
                result.total(), totalPages);
    }

    public AuctionListing find(long listingId) {
        return database.query(c -> listings.findById(c, listingId).orElse(null));
    }

    /** Price is for the entire held stack. */
    public Outcome create(UUID seller, ItemStack heldStack, long totalPrice) {
        if (seller == null || heldStack == null || heldStack.isEmpty()) {
            return Outcome.fail(Result.INVALID_ITEM, "Возьмите продаваемый предмет в основную руку.");
        }
        if (totalPrice <= 0) return Outcome.fail(Result.INVALID_PRICE, "Цена должна быть больше нуля.");
        ItemPolicy.PolicyResult policy = ItemPolicy.check(heldStack, settings);
        if (!policy.allowed()) return Outcome.fail(Result.INVALID_ITEM, "Этот предмет нельзя выставить на аукцион.");
        if (settings.maxActiveListingsPerPlayer() > 0
                && database.query(c -> listings.countSimpleActive(c, seller)) >= settings.maxActiveListingsPerPlayer()) {
            return Outcome.fail(Result.OVER_LIMIT, "Достигнут лимит активных лотов.");
        }

        ItemStack custody = heldStack.copy();
        ItemSnapshot snapshot;
        try {
            snapshot = codec.encode(custody);
        } catch (ItemCodecException e) {
            return Outcome.fail(Result.INVALID_ITEM, "Не удалось сохранить все данные предмета.");
        }
        long now = System.currentTimeMillis();
        AuctionListing draft = AuctionListing.newListing(seller, snapshot, totalPrice)
                .fee(settings.listingFeeMinor()).commissionBps(settings.commissionBps())
                .times(now, now + Duration.ofHours(settings.listingDurationHours()).toMillis()).build();
        final long listingId;
        try {
            listingId = database.inTransaction(c -> {
                long id = listings.insert(c, draft);
                listings.markSimple(c, id, MarketCategoryClassifier.classify(custody).id(), now);
                return id;
            });
        } catch (RuntimeException e) {
            return Outcome.fail(Result.DATABASE_FAILED, "Не удалось создать лот.");
        }

        ItemStack exact = custody.copy();
        exact.setCount(1);
        if (!inventory.tryTake(seller, exact, custody.getCount())) {
            closeFailedCreation(listingId, "inventory changed");
            return Outcome.fail(Result.INVALID_ITEM, "Предмет уже изменился или исчез из инвентаря.");
        }
        try {
            AuctionListing active = database.inTransaction(c -> {
                if (!listings.setSimpleState(c, listingId, "PENDING", "ACTIVE")) {
                    throw new DatabaseException("listing activation conflict " + listingId);
                }
                AuctionListing current = listings.findById(c, listingId).orElseThrow();
                AuctionOperation op = operation(OperationType.CREATE_LISTING,
                        "simple:create:" + listingId, "simple-create-" + listingId,
                        listingId, seller, null, OperationPhase.COMPLETE, now);
                operations.insert(c, op);
                operations.applyRetry(c, op.operationId(), 0, op.toCompleted(now));
                return current;
            });
            return Outcome.ok("Лот выставлен.", active, null);
        } catch (RuntimeException e) {
            // Custody is durably represented by the PENDING listing. Never hand it back here:
            // a late DB success plus a refund would duplicate the stack.
            LOGGER.error("Simple listing {} locked inventory but could not activate", listingId, e);
            return new Outcome(Result.ACCEPTED_PENDING,
                    "Лот принят и будет проверен системой.", null, null);
        }
    }

    /** Recovery policy: a PENDING listing means item custody is indeterminate after a crash. */
    public int quarantinePendingCreations(int limit) {
        try {
            return database.inTransaction(c -> {
                try (var ps = c.prepareStatement("UPDATE auction_simple_listing_ids SET state='MANUAL_REVIEW' "
                        + "WHERE listing_id IN (SELECT listing_id FROM auction_simple_listing_ids "
                        + "WHERE state='PENDING' ORDER BY listing_id LIMIT ?)")) {
                    ps.setInt(1, Math.max(1, limit));
                    return ps.executeUpdate();
                } catch (java.sql.SQLException e) {
                    throw new DatabaseException("quarantine pending simple listings failed", e);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.error("Could not quarantine pending simple listings", e);
            return 0;
        }
    }

    public Outcome cancel(UUID actor, long listingId) {
        long now = System.currentTimeMillis();
        try {
            return database.inTransaction(c -> {
                AuctionListing current = listings.findById(c, listingId).orElse(null);
                if (current == null || current.status() != ListingStatus.ACTIVE) {
                    return Outcome.fail(Result.NOT_FOUND, "Лот уже недоступен.");
                }
                if (!current.sellerUuid().equals(actor)) {
                    return Outcome.fail(Result.NOT_YOURS, "Это чужой лот.");
                }
                AuctionListing cancelled = current.toCancelled("seller cancelled", null, now);
                if (!listings.applyState(c, current, cancelled)
                        || !listings.setSimpleState(c, listingId, "ACTIVE", "CLOSED")) {
                    throw new DatabaseException("listing cancel conflict " + listingId);
                }
                String opId = "simple-cancel-" + listingId;
                long deliveryId = deliveries.insert(c, claimable(current.sellerUuid(), listingId,
                        opId, DeliveryType.CANCELLED_RETURN, current.item(), "simple:return:" + listingId, now));
                AuctionOperation op = operation(OperationType.CANCEL, "simple:cancel:" + listingId,
                        opId, listingId, actor, null, OperationPhase.COMPLETE, now);
                operations.insert(c, op);
                operations.applyRetry(c, op.operationId(), 0, op.toCompleted(now));
                return Outcome.ok("Лот снят с продажи.", cancelled, deliveryId);
            });
        } catch (RuntimeException e) {
            LOGGER.warn("Simple listing cancel failed {}: {}", listingId, e.getMessage());
            return Outcome.fail(Result.DATABASE_FAILED, "Не удалось снять лот. Попробуйте ещё раз.");
        }
    }

    public Outcome purchase(UUID buyer, long listingId) {
        long now = System.currentTimeMillis();
        String purchaseId = UUID.randomUUID().toString();
        AuctionListing reserved;
        try {
            reserved = database.inTransaction(c -> {
                AuctionListing current = listings.findById(c, listingId).orElse(null);
                if (current == null || current.status() != ListingStatus.ACTIVE) return null;
                if (current.sellerUuid().equals(buyer) && !settings.allowSelfPurchase()) {
                    throw new OwnListingException();
                }
                AuctionListing next = current.toReserved(buyer, purchaseId, now,
                        now + RESERVATION_MILLIS, now);
                if (!listings.applyState(c, current, next)) return null;
                operations.insert(c, operation(OperationType.PURCHASE,
                        "simple:purchase:" + purchaseId, "simple-buy-" + purchaseId,
                        listingId, buyer, current.sellerUuid(), OperationPhase.ESCROW_RESERVE, now));
                return listings.findById(c, listingId).orElse(next);
            });
        } catch (OwnListingException e) {
            return Outcome.fail(Result.OWN_LISTING, "Свой лот можно только снять с продажи.");
        } catch (RuntimeException e) {
            return Outcome.fail(Result.DATABASE_FAILED, "Не удалось начать покупку.");
        }
        if (reserved == null) return Outcome.fail(Result.CHANGED, "Этот лот уже купили или сняли.");

        String ref = escrowRef(reserved);
        EconomyGateway.ReserveResult hold = economy.reserve(buyer, reserved.priceMinor(), ref,
                "Покупка на аукционе: " + reserved.item().displayLabel(), "simple:reserve:" + purchaseId);
        if (!hold.isSuccessOrIdempotent()) {
            if (hold.status() == EconomyGateway.ReserveStatus.INSUFFICIENT_FUNDS) {
                releaseReservation(reserved, "insufficient funds");
                return Outcome.fail(Result.INSUFFICIENT_FUNDS, "Недостаточно денег.");
            }
            return new Outcome(Result.ACCEPTED_PENDING,
                    "Покупка принята и безопасно завершается.", reserved, null);
        }
        return settleAndFinalize(reserved);
    }

    /** Bounded startup/runtime recovery for fixed-price purchases. */
    public int recoverReserved(int limit) {
        int completed = 0;
        for (AuctionListing listing : database.query(c -> listings.simpleReserved(c, limit))) {
            EconomyGateway.LookupResult lookup = economy.find(escrowRef(listing));
            if (lookup.status() == EconomyGateway.LookupStatus.NOT_FOUND) {
                EconomyGateway.ReserveResult reserve = economy.reserve(listing.buyerUuid(), listing.priceMinor(),
                        escrowRef(listing), "Покупка на аукционе: " + listing.item().displayLabel(),
                        "simple:reserve:" + listing.reservationId());
                if (reserve.status() == EconomyGateway.ReserveStatus.INSUFFICIENT_FUNDS) {
                    releaseReservation(listing, "recovery insufficient funds");
                    completed++;
                    continue;
                }
                if (!reserve.isSuccessOrIdempotent()) continue;
                lookup = economy.find(escrowRef(listing));
            }
            if (lookup.status() == EconomyGateway.LookupStatus.FOUND
                    && (lookup.holding().state() == EconomyGateway.HoldingState.RESERVED
                    || lookup.holding().state() == EconomyGateway.HoldingState.CAPTURED)) {
                if (settleAndFinalize(listing).success()) completed++;
            } else if (lookup.status() == EconomyGateway.LookupStatus.FOUND
                    && lookup.holding().state() == EconomyGateway.HoldingState.RELEASED) {
                releaseReservation(listing, "escrow released");
                completed++;
            }
        }
        return completed;
    }

    public int expire(int limit) {
        int expired = 0;
        long now = System.currentTimeMillis();
        for (AuctionListing listing : database.query(c -> listings.simpleExpiredActive(c, now, limit))) {
            try {
                boolean done = database.inTransaction(c -> {
                    AuctionListing current = listings.findById(c, listing.listingId()).orElse(null);
                    if (current == null || current.status() != ListingStatus.ACTIVE) return false;
                    AuctionListing next = current.toExpired(now);
                    if (!listings.applyState(c, current, next)
                            || !listings.setSimpleState(c, current.listingId(), "ACTIVE", "CLOSED")) return false;
                    String opId = "simple-expire-" + current.listingId();
                    deliveries.insert(c, claimable(current.sellerUuid(), current.listingId(), opId,
                            DeliveryType.EXPIRED_RETURN, current.item(),
                            "simple:expire:" + current.listingId(), now));
                    AuctionOperation op = operation(OperationType.EXPIRE,
                            "simple:expire:" + current.listingId(), opId, current.listingId(),
                            current.sellerUuid(), null, OperationPhase.COMPLETE, now);
                    operations.insert(c, op);
                    operations.applyRetry(c, opId, 0, op.toCompleted(now));
                    return true;
                });
                if (done) expired++;
            } catch (RuntimeException e) {
                LOGGER.warn("Could not expire simple listing {}", listing.listingId(), e);
            }
        }
        return expired;
    }

    private Outcome settleAndFinalize(AuctionListing listing) {
        long calculatedCommission = commission(listing.priceMinor(), listing.commissionBps());
        long calculatedSellerNet = listing.priceMinor() - calculatedCommission;
        if (calculatedSellerNet <= 0) {
            // EconomyGateway intentionally rejects zero credits. For a 100% fee the
            // entire amount belongs to treasury and settlement must still succeed.
            calculatedSellerNet = 0;
            calculatedCommission = listing.priceMinor();
        }
        final long commission = calculatedCommission;
        final long sellerNet = calculatedSellerNet;
        List<EconomyGateway.Credit> credits = new ArrayList<>(2);
        if (sellerNet > 0) credits.add(new EconomyGateway.Credit(listing.sellerUuid(), sellerNet, "seller"));
        if (commission > 0) credits.add(new EconomyGateway.Credit(economy.treasury(), commission, "commission"));
        EconomyGateway.SettleResult settled = economy.settle(escrowRef(listing), credits,
                "Продажа на аукционе: " + listing.item().displayLabel(),
                "simple:settle:" + listing.reservationId());
        if (!settled.isSuccessOrIdempotent()) {
            return new Outcome(Result.ACCEPTED_PENDING,
                    "Покупка принята и безопасно завершается.", listing, null);
        }
        long now = System.currentTimeMillis();
        try {
            return database.inTransaction(c -> {
                AuctionListing current = listings.findById(c, listing.listingId()).orElse(null);
                if (current == null) throw new DatabaseException("listing disappeared " + listing.listingId());
                if (current.status() == ListingStatus.SOLD) {
                    Long delivery = deliveries.findByDedupeKey(c, "simple:purchase:" + listing.listingId())
                            .map(AuctionDelivery::deliveryId).orElse(null);
                    return Outcome.ok("Лот куплен.", current, delivery);
                }
                if (current.status() != ListingStatus.RESERVED
                        || !listing.reservationId().equals(current.reservationId())) {
                    throw new DatabaseException("purchase reservation changed " + listing.listingId());
                }
                AuctionListing sold = current.toSold(current.buyerUuid(), now);
                if (!listings.applyState(c, current, sold)
                        || !listings.setSimpleState(c, current.listingId(), "ACTIVE", "CLOSED")) {
                    throw new DatabaseException("purchase finalize conflict " + current.listingId());
                }
                String opId = "simple-buy-" + current.reservationId();
                sales.insert(c, AuctionSale.newSale(current.sellerUuid(), current.buyerUuid(),
                                current.priceMinor(), escrowRef(current), current.item().hash(), now)
                        .listingId(current.listingId()).purchaseOperationId(opId)
                        .commissionMinor(commission).sellerNetMinor(sellerNet).build());
                long deliveryId = deliveries.insert(c, claimable(current.buyerUuid(), current.listingId(),
                        opId, DeliveryType.PURCHASED, current.item(),
                        "simple:purchase:" + current.listingId(), now));
                operations.findById(c, opId).ifPresent(op -> operations.applyRetry(c, opId,
                        op.attemptCount(), op.toCompleted(now)));
                return Outcome.ok("Лот куплен.", sold, deliveryId);
            });
        } catch (RuntimeException e) {
            LOGGER.error("Simple purchase {} settled but finalization is pending", listing.listingId(), e);
            return new Outcome(Result.ACCEPTED_PENDING,
                    "Оплата прошла. Предмет появится в получении после восстановления.", listing, null);
        }
    }

    private void releaseReservation(AuctionListing reserved, String reason) {
        long now = System.currentTimeMillis();
        try {
            database.inTransaction(c -> {
                AuctionListing current = listings.findById(c, reserved.listingId()).orElse(null);
                if (current != null && current.status() == ListingStatus.RESERVED
                        && reserved.reservationId().equals(current.reservationId())) {
                    listings.applyState(c, current, current.releaseReservation(now));
                }
                operations.findById(c, "simple-buy-" + reserved.reservationId()).ifPresent(op ->
                        operations.applyRetry(c, op.operationId(), op.attemptCount(),
                                op.toFailed(reason, null, now)));
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.error("Could not release simple reservation {}", reserved.listingId(), e);
        }
    }

    private void closeFailedCreation(long listingId, String reason) {
        long now = System.currentTimeMillis();
        try {
            database.inTransaction(c -> {
                AuctionListing current = listings.findById(c, listingId).orElse(null);
                if (current != null && current.status() == ListingStatus.ACTIVE) {
                    listings.applyState(c, current, current.toCancelled(reason, null, now));
                    listings.setSimpleState(c, listingId, "PENDING", "CLOSED");
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.error("Could not close failed simple listing {}", listingId, e);
        }
    }

    private static AuctionDelivery claimable(UUID owner, long listingId, String opId,
                                             DeliveryType type, ItemSnapshot item,
                                             String dedupe, long now) {
        return AuctionDelivery.newDelivery(owner, listingId, opId, type, item, now)
                .dedupeKey(dedupe).build().toClaimable(now, "simple-claim-" + UUID.randomUUID());
    }

    private static AuctionOperation operation(OperationType type, String key, String id,
                                              long listingId, UUID actor, UUID target,
                                              OperationPhase phase, long now) {
        return AuctionOperation.newOperation(type, key, now).operationId(id).listingId(listingId)
                .actor(actor).target(target).phase(phase).build();
    }

    private static long commission(long gross, int bps) {
        return Math.addExact(Math.multiplyExact(gross / 10_000L, bps),
                Math.multiplyExact(gross % 10_000L, bps) / 10_000L);
    }

    private static String escrowRef(AuctionListing listing) {
        return "vauction:simple:" + listing.listingId() + ":" + listing.reservationId();
    }

    private static final class OwnListingException extends RuntimeException {}
}

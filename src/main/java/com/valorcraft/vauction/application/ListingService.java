package com.valorcraft.vauction.application;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.domain.listing.AuctionListing;
import com.valorcraft.vauction.domain.operation.AuctionOperation;
import com.valorcraft.vauction.domain.operation.OperationType;
import com.valorcraft.vauction.item.ItemCodecError;
import com.valorcraft.vauction.item.ItemCodecException;
import com.valorcraft.vauction.item.ItemPolicy;
import com.valorcraft.vauction.item.ItemSnapshot;
import com.valorcraft.vauction.item.ItemStackCodec;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.ListingRepository;
import com.valorcraft.vauction.persistence.OperationRepository;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Создание лота (подготовка): ItemPolicy → codec → запись лота + журнал операции
 * в единой транзакции. Деньги/escrow — на следующем этапе через EconomyGateway.
 * Вызывать только с серверного потока.
 */
public final class ListingService {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    public enum Failure {
        POLICY_NOT_ALLOWED,
        ITEM_TOO_LARGE,
        ITEM_ENCODING_ERROR,
        DATABASE_FAILED
    }

    public record ListingCreateResult(boolean success, Failure failure, String detail,
                                      Long listingId, String operationId) {

        public static ListingCreateResult succeeded(long listingId, String operationId) {
            return new ListingCreateResult(true, null, null, listingId, operationId);
        }

        public static ListingCreateResult failed(Failure failure, String detail) {
            return new ListingCreateResult(false, failure, detail, null, null);
        }
    }

    private final DatabaseManager database;
    private final ListingRepository listings;
    private final OperationRepository operations;
    private final ItemStackCodec codec;
    private final OperationRecorder recorder;

    public ListingService(DatabaseManager database, ListingRepository listings,
                          OperationRepository operations, ItemStackCodec codec) {
        this.database = database;
        this.listings = listings;
        this.operations = operations;
        this.codec = codec;
        this.recorder = new OperationRecorder(database, operations);
    }

    /** Создать лот. Комиссии — снимок конфига на момент создания. */
    public ListingCreateResult createListing(UUID seller, ItemStack stack, long priceMinor,
                                             AuctionSettings settings) {
        if (seller == null) {
            throw new IllegalArgumentException("seller must not be null");
        }
        ItemPolicy.PolicyResult policy = ItemPolicy.check(stack, settings);
        if (!policy.allowed()) {
            return ListingCreateResult.failed(Failure.POLICY_NOT_ALLOWED,
                    policy.failure() + ": " + policy.detail());
        }

        ItemSnapshot snapshot;
        try {
            snapshot = codec.encode(stack.copy());
        } catch (ItemCodecException e) {
            LOGGER.warn("Не удалось закодировать предмет продавца {}: {}", seller, e.getMessage());
            return ListingCreateResult.failed(
                    e.error() == ItemCodecError.ITEM_TOO_LARGE
                            ? Failure.ITEM_TOO_LARGE
                            : Failure.ITEM_ENCODING_ERROR,
                    e.getMessage());
        }

        long now = Instant.now().toEpochMilli();
        AuctionListing listing = AuctionListing.newListing(seller, snapshot, priceMinor)
                .fee(settings.listingFeeMinor())
                .commissionBps(settings.commissionBps())
                .times(now, now + Duration.ofHours(settings.listingDurationHours()).toMillis())
                .build();

        try {
            long listingId = database.inTransaction(connection -> {
                long id = listings.insert(connection, listing);
                AuctionOperation operation = AuctionOperation
                        .newOperation(OperationType.CREATE_LISTING,
                                "create:" + seller + ":" + snapshot.hash(), System.currentTimeMillis())
                        .operationId("vl-" + UUID.randomUUID())
                        .listingId(id)
                        .actor(seller)
                        .build();
                operations.insert(connection, operation);
                operations.applyRetry(connection, operation.operationId(), 0,
                        operation.toCompleted(System.currentTimeMillis()));
                return id;
            });
            LOGGER.info("Лот создан: listingId={}, seller={}, {}", listingId, seller, snapshot.toLogSummary());
            return ListingCreateResult.succeeded(listingId, "vl-" + listingId);
        } catch (Exception e) {
            LOGGER.error("Ошибка при создании лота игрока {}: {}", seller, e.getMessage(), e);
            return ListingCreateResult.failed(Failure.DATABASE_FAILED, e.getMessage());
        }
    }
}
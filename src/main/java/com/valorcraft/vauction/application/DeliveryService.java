package com.valorcraft.vauction.application;

import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryType;
import com.valorcraft.vauction.item.ItemSnapshot;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.OperationRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * Создание delivery-записей (что вернуть/выдать игроку). Фактическое добавление
 * предмета в инвентарь реализуется на следующем этапе; пока — persistence.
 * Вызывать только с серверного потока.
 */
public final class DeliveryService {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    private final DatabaseManager database;
    private final DeliveryRepository deliveries;

    public DeliveryService(DatabaseManager database, DeliveryRepository deliveries) {
        this.database = database;
        this.deliveries = deliveries;
    }

    /**
     * Создать delivery PENDING с dedupe_key (уникальность — в БД, повторный вызов
     * с тем же ключом упадёт с DatabaseException).
     */
    public DeliveryCreateResult create(UUID playerUuid, long listingId, String operationId,
                                       DeliveryType type, ItemSnapshot item, String dedupeKey) {
        if (playerUuid == null || item == null || dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("playerUuid/item/dedupeKey обязательны");
        }
        try {
            long id = database.inTransaction(connection ->
                    deliveries.insert(connection, AuctionDelivery
                            .newDelivery(playerUuid, listingId, operationId, type, item,
                                    System.currentTimeMillis())
                            .dedupeKey(dedupeKey)
                            .build()));
            LOGGER.info("Создана delivery: id={}, player={}, listing={}, type={}, {}",
                    id, playerUuid, listingId, type, item.toLogSummary());
            return new DeliveryCreateResult(true, id, null, null);
        } catch (Exception e) {
            LOGGER.warn("Не удалось создать delivery для игрока {} (listing={}): {}",
                    playerUuid, listingId, e.getMessage());
            return new DeliveryCreateResult(false, -1L, DeliveryCreateResult.Failure.DATABASE_OR_DUPLICATE,
                    e.getMessage());
        }
    }

    public record DeliveryCreateResult(boolean success, long deliveryId,
                                       DeliveryCreateResult.Failure failure, String detail) {

        public enum Failure {
            DATABASE_OR_DUPLICATE
        }
    }
}
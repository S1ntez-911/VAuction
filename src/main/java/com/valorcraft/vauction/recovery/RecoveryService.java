package com.valorcraft.vauction.recovery;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Точка роста для сканирования застрявших listing/delivery/operation при старте
 * сервера и автоисправлений. Полноценная реализация — следующий этап.
 */
public final class RecoveryService {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    public record ScanReport(int failedListings, int pendingDeliveries, int operationsInReview) {}

    /** Ничего не делает — заглушка следующего этапа. */
    public ScanReport scan() {
        LOGGER.debug("RecoveryService: сканирование отложено до следующего этапа");
        return new ScanReport(0, 0, 0);
    }
}
package com.valorcraft.vauction.recovery;

import com.valorcraft.vauction.application.AuctionService;
import com.valorcraft.vauction.application.AuctionWorkLimits;
import com.valorcraft.vauction.application.WorkBudget;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderProcessingState;
import com.valorcraft.vauction.domain.trade.Trade;
import com.valorcraft.vauction.economy.EconomyGateway;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.DeliveryRepository;
import com.valorcraft.vauction.persistence.OrderRepository;
import com.valorcraft.vauction.persistence.TradeRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Recovery: доведение торгового ядра до консистентного состояния при старте.
 * <p>
 * Что восстанавливается (все шаги идемпотентны):
 * <ol>
 *   <li><b>Незавершённые fills</b> (crash между S2/S3/S4): Trade в PENDING →
 *       settlement по его escrow-ref + релок новой эпохи + фиксация
 *       (delivery CLAIMABLE, order эпоха, op COMPLETE) — {@link AuctionService#resumeFill};</li>
 *   <li><b>Обеспечение BUY-заявок</b>: активные BUY должны иметь escrow
 *       на {@code remaining * limit}. Нет резерва/освобождён/меньше нужного →
 *       повторный reserve; невозможно (недостаточно средств) → MANUAL_REVIEW;</li>
 *   <li><b>Зависшие CLAIMING</b> (сервер умер в момент выдачи): результат
 *       неопределён, поэтому письмо уходит в FAILED/manual review. Автоповтор
 *       мог бы задвоить уже сохранённый Minecraft предмет.</li>
 * </ol>
 */
public final class RecoveryService {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    private final DatabaseManager database;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final DeliveryRepository deliveries;
    private final EconomyGateway economy;
    private final AuctionService auction;

    private enum BackingResult { OK, RESTORED, RETRY, MANUAL_REVIEW }

    public RecoveryService(DatabaseManager database, OrderRepository orders, TradeRepository trades,
                           DeliveryRepository deliveries, EconomyGateway economy,
                           AuctionService auction) {
        this.database = database;
        this.orders = orders;
        this.trades = trades;
        this.deliveries = deliveries;
        this.economy = economy;
        this.auction = auction;
    }

    /** Итоги одного прохода. */
    public record ScanReport(int fillsFinished, int escrowsRestored, int claimsQuarantined,
                             int ordersInManualReview, int operationsAttempted,
                             boolean backlogRemaining) {

        public int total() {
            return fillsFinished + escrowsRestored + claimsQuarantined + ordersInManualReview;
        }
    }

    /** Compatibility entry point used by tests; production startup calls {@link #startupScan()}. */
    public ScanReport scan() {
        return startupScan();
    }

    /**
     * Deep startup reconciliation. It is allowed to inspect every active BUY, but only through
     * keyset-paginated targeted queries. Database failures propagate so startup fails closed.
     */
    public ScanReport startupScan() {
        Stats stats = new Stats();
        pagePendingTrades(stats);
        pageProcessingOrders(stats);
        pageActiveBuys(stats);
        pageClaimingDeliveries(stats);
        return stats.report(false);
    }

    /** Bounded runtime recovery: pending trades, processing orders and CLAIMING only. */
    public ScanReport runtimeSlice(WorkBudget budget) {
        Stats stats = new Stats();
        try {
            int queryLimit = Math.min(AuctionWorkLimits.TARGETED_QUERY_BATCH,
                    Math.min(AuctionWorkLimits.MAX_RUNTIME_RECOVERY_OPERATIONS,
                            Math.max(1, budget.remaining())));
            List<Trade> pending = database.query(c -> trades.findPending(c, queryLimit));
            List<Order> processing = database.query(c -> orders.listProcessing(c, queryLimit));
            List<AuctionDelivery> claiming = database.query(c ->
                    deliveries.listByState(c, DeliveryState.CLAIMING, queryLimit));
            int max = Math.max(pending.size(), Math.max(processing.size(), claiming.size()));
            for (int i = 0; i < max && !budget.exhausted()
                    && stats.operations < AuctionWorkLimits.MAX_RUNTIME_RECOVERY_OPERATIONS; i++) {
                if (i < pending.size() && budget.tryAcquire()) recoverTrade(pending.get(i), stats);
                if (stats.operations < AuctionWorkLimits.MAX_RUNTIME_RECOVERY_OPERATIONS
                        && i < processing.size() && budget.tryAcquire()) recoverProcessing(processing.get(i), stats);
                if (stats.operations < AuctionWorkLimits.MAX_RUNTIME_RECOVERY_OPERATIONS
                        && i < claiming.size() && budget.tryAcquire()) recoverClaim(claiming.get(i), stats);
            }
            boolean backlog = budget.exhausted()
                    || pending.size() == queryLimit || processing.size() == queryLimit
                    || claiming.size() == queryLimit;
            ScanReport report = stats.report(backlog);
            if (backlog) {
                LOGGER.warn("VAuction recovery backlog remains after bounded slice (attempted={})",
                        report.operationsAttempted());
            }
            return report;
        } catch (RuntimeException e) {
            LOGGER.error("Runtime recovery slice failed: {}", e.getMessage(), e);
            return stats.report(true);
        }
    }

    private void pagePendingTrades(Stats stats) {
        long cursorTime = Long.MIN_VALUE;
        String cursorId = "";
        while (true) {
            long time = cursorTime;
            String id = cursorId;
            List<Trade> page = database.query(c -> trades.findPendingAfter(c, time, id,
                    AuctionWorkLimits.STARTUP_PAGE_SIZE));
            if (page.isEmpty()) return;
            for (Trade trade : page) recoverTrade(trade, stats);
            Trade last = page.get(page.size() - 1);
            cursorTime = last.createdAt();
            cursorId = last.tradeId().toString();
        }
    }

    private void pageProcessingOrders(Stats stats) {
        long cursorTime = Long.MIN_VALUE;
        String cursorId = "";
        while (true) {
            long time = cursorTime;
            String id = cursorId;
            List<Order> page = database.query(c -> orders.listProcessingAfter(c, time, id,
                    AuctionWorkLimits.STARTUP_PAGE_SIZE));
            if (page.isEmpty()) return;
            for (Order order : page) recoverProcessing(order, stats);
            Order last = page.get(page.size() - 1);
            cursorTime = last.updatedAt();
            cursorId = last.orderId().toString();
        }
    }

    private void pageActiveBuys(Stats stats) {
        long cursorTime = Long.MIN_VALUE;
        String cursorId = "";
        while (true) {
            long time = cursorTime;
            String id = cursorId;
            List<Order> page = database.query(c -> orders.listActiveBuysAfter(c, time, id,
                    AuctionWorkLimits.STARTUP_PAGE_SIZE));
            if (page.isEmpty()) return;
            for (Order order : page) {
                stats.operations++;
                BackingResult result = ensureEscrowBacking(order);
                if (result == BackingResult.RESTORED) stats.escrows++;
                if (result == BackingResult.MANUAL_REVIEW) stats.manual++;
            }
            Order last = page.get(page.size() - 1);
            cursorTime = last.createdAt();
            cursorId = last.orderId().toString();
        }
    }

    private void pageClaimingDeliveries(Stats stats) {
        long cursor = 0L;
        while (true) {
            long after = cursor;
            List<AuctionDelivery> page = database.query(c -> deliveries.listByStateAfter(c,
                    DeliveryState.CLAIMING, after, AuctionWorkLimits.STARTUP_PAGE_SIZE));
            if (page.isEmpty()) return;
            for (AuctionDelivery delivery : page) recoverClaim(delivery, stats);
            cursor = page.get(page.size() - 1).deliveryId();
        }
    }

    private void recoverTrade(Trade trade, Stats stats) {
        stats.operations++;
        if (auction.resumeFill(trade)) stats.fills++;
    }

    private void recoverProcessing(Order order, Stats stats) {
        stats.operations++;
        if (order.processingState() == OrderProcessingState.CANCEL
                || order.processingState() == OrderProcessingState.EXPIRE) {
            if (auction.resumePendingOrder(order.orderId())) stats.fills++;
        } else if (order.processingState() == OrderProcessingState.RESERVE) {
            BackingResult result = ensureEscrowBacking(order);
            if ((result == BackingResult.OK || result == BackingResult.RESTORED)
                    && auction.activateReservedBuy(order.orderId()) != null
                    && result == BackingResult.RESTORED) stats.escrows++;
            if (result == BackingResult.MANUAL_REVIEW) stats.manual++;
        } else if (order.processingState() == OrderProcessingState.ITEM_LOCK) {
            auction.forceOrderManualReview(order.orderId(),
                    "indeterminate SELL inventory lock after restart");
            stats.manual++;
        }
    }

    private void recoverClaim(AuctionDelivery delivery, Stats stats) {
        stats.operations++;
        if (auction.quarantineClaim(delivery.deliveryId())) stats.claims++;
    }

    private static final class Stats {
        int fills;
        int escrows;
        int claims;
        int manual;
        int operations;

        ScanReport report(boolean backlog) {
            return new ScanReport(fills, escrows, claims, manual, operations, backlog);
        }
    }

    /**
     * Проверка/восстановление полного обеспечения buy-ордера.
     * @return true если обеспечение в порядке (или восстановлено).
     */
    private BackingResult ensureEscrowBacking(Order order) {
        String ref = order.escrowReference();
        if (ref == null || ref.isBlank()) {
            auction.forceOrderManualReview(order.orderId(), "BUY без escrow-reference");
            return BackingResult.MANUAL_REVIEW;
        }
        long need;
        try {
            need = Math.multiplyExact((long) order.remainingQuantity(), order.pricePerUnit());
        } catch (ArithmeticException e) {
            auction.forceOrderManualReview(order.orderId(), "overflow обеспечения");
            return BackingResult.MANUAL_REVIEW;
        }
        if (need <= 0) {
            return BackingResult.OK;
        }
        EconomyGateway.LookupResult current = economy.find(ref);
        if (current.status() == EconomyGateway.LookupStatus.FOUND) {
            EconomyGateway.Holding holding = current.holding();
            if (holding.state() == EconomyGateway.HoldingState.RESERVED && holding.amount() == need) {
                return BackingResult.OK;
            }
            auction.forceOrderManualReview(order.orderId(),
                    "escrow не соответствует активному BUY: " + ref + " state="
                            + holding.state() + " amount=" + holding.amount() + " need=" + need);
            return BackingResult.MANUAL_REVIEW;
        }
        if (current.status() == EconomyGateway.LookupStatus.TRANSIENT_FAILURE
                || current.status() == EconomyGateway.LookupStatus.FAILED) {
            LOGGER.warn("Временная ошибка чтения escrow {}; повтор будет выполнен позже", ref);
            return BackingResult.RETRY;
        }
        if (current.status() != EconomyGateway.LookupStatus.NOT_FOUND) {
            auction.forceOrderManualReview(order.orderId(), "ошибка чтения escrow " + ref);
            return BackingResult.MANUAL_REVIEW;
        }
        // NOT_FOUND после durable create-intent: reserve ещё не успел выполниться.
        EconomyGateway.ReserveResult rr = economy.reserve(order.ownerUuid(), need, ref,
                "Заявка на покупку: " + order.item().displayLabel(), "va:recover:" + ref);
        if (rr.isSuccessOrIdempotent()) {
            LOGGER.info("Восстановлен escrow {} ({}) на {}", ref, order.orderId(), need);
            return BackingResult.RESTORED;
        }
        if (rr.status() == EconomyGateway.ReserveStatus.INSUFFICIENT_FUNDS) {
            auction.forceOrderManualReview(order.orderId(),
                    "недостаточно средств для восстановления escrow " + ref);
            return BackingResult.MANUAL_REVIEW;
        }
        if (rr.status() == EconomyGateway.ReserveStatus.TRANSIENT_FAILURE
                || rr.status() == EconomyGateway.ReserveStatus.FAILED) {
            LOGGER.warn("Временная ошибка восстановления escrow {}; повтор будет выполнен позже: {}",
                    ref, rr.status());
            return BackingResult.RETRY;
        }
        LOGGER.warn("Восстановление escrow {} не удалось: {}", ref, rr.status());
        auction.forceOrderManualReview(order.orderId(), "escrow " + rr.status() + ": " + ref);
        return BackingResult.MANUAL_REVIEW;
    }
}

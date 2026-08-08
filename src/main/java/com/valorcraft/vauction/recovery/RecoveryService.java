package com.valorcraft.vauction.recovery;

import com.valorcraft.vauction.application.AuctionService;
import com.valorcraft.vauction.domain.delivery.AuctionDelivery;
import com.valorcraft.vauction.domain.delivery.DeliveryState;
import com.valorcraft.vauction.domain.order.Order;
import com.valorcraft.vauction.domain.order.OrderSide;
import com.valorcraft.vauction.domain.order.OrderProcessingState;
import com.valorcraft.vauction.domain.trade.Trade;
import com.valorcraft.vauction.domain.trade.TradeState;
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

    private static final int MAX_ACTIVE_ORDERS = 50_000;

    private final DatabaseManager database;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final DeliveryRepository deliveries;
    private final EconomyGateway economy;
    private final AuctionService auction;

    private enum BackingResult { OK, RESTORED, MANUAL_REVIEW }

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
                             int ordersInManualReview) {

        public int total() {
            return fillsFinished + escrowsRestored + claimsQuarantined + ordersInManualReview;
        }
    }

    /** Полный проход; никогда не бросает исключений (логирует и продолжает). */
    public ScanReport scan() {
        int fillsFinished = 0;
        int escrowsRestored = 0;
        int claimsQuarantined = 0;
        int manualReviews = 0;
        try {
            // 1. незавершённые fills (trade PENDING → деньги+фиксация)
            List<Trade> pendingTrades = database.query(conn -> trades.findAll(conn)).stream()
                    .filter(t -> t.state() == TradeState.PENDING)
                    .toList();
            for (Trade t : pendingTrades) {
                if (auction.resumeFill(t)) {
                    fillsFinished++;
                }
            }

            // 2. Durable cancel/expiry sagas and ambiguous inventory mutations.
            for (Order order : database.query(conn -> orders.listProcessing(conn, MAX_ACTIVE_ORDERS))) {
                if (order.processingState() == OrderProcessingState.CANCEL
                        || order.processingState() == OrderProcessingState.EXPIRE) {
                    if (auction.resumePendingOrder(order.orderId())) {
                        fillsFinished++;
                    }
                } else if (order.processingState() == OrderProcessingState.ITEM_LOCK) {
                    auction.forceOrderManualReview(order.orderId(),
                            "indeterminate SELL inventory lock after restart");
                    manualReviews++;
                }
            }

            // 3. обеспечение активных BUY-заявок
            for (Order order : database.query(conn -> orders.listActive(conn, MAX_ACTIVE_ORDERS))) {
                if (order.side() != OrderSide.BUY) {
                    continue;
                }
                BackingResult result = ensureEscrowBacking(order);
                if (result == BackingResult.RESTORED) {
                    escrowsRestored++;
                } else if (result == BackingResult.MANUAL_REVIEW) {
                    manualReviews++;
                }
            }

            // 4. зависшие попытки выдачи: fail closed, без автоматического дюпа
            for (AuctionDelivery d : database.query(conn ->
                    deliveries.listByState(conn, DeliveryState.CLAIMING))) {
                if (auction.quarantineClaim(d.deliveryId())) {
                    claimsQuarantined++;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("Recovery scan прерван: {}", e.getMessage(), e);
        }
        ScanReport report = new ScanReport(fillsFinished, escrowsRestored, claimsQuarantined,
                manualReviews);
        if (report.total() > 0) {
            LOGGER.info("RecoveryService: fills={}, escrows={}, claims={}, review={}",
                    fillsFinished, escrowsRestored, claimsQuarantined, manualReviews);
        }
        return report;
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
        if (current.status() == EconomyGateway.LookupStatus.FAILED) {
            auction.forceOrderManualReview(order.orderId(), "ошибка чтения escrow " + ref);
            return BackingResult.MANUAL_REVIEW;
        }
        // NOT_FOUND после durable create-intent: reserve ещё не успел выполниться.
        EconomyGateway.ReserveResult rr = economy.reserve(order.ownerUuid(), need, ref,
                "recovery " + order.orderId(), "va:recover:" + ref);
        if (rr.isSuccessOrIdempotent()) {
            LOGGER.info("Восстановлен escrow {} ({}) на {}", ref, order.orderId(), need);
            return BackingResult.RESTORED;
        }
        if (rr.status() == EconomyGateway.ReserveStatus.INSUFFICIENT_FUNDS) {
            auction.forceOrderManualReview(order.orderId(),
                    "недостаточно средств для восстановления escrow " + ref);
            return BackingResult.MANUAL_REVIEW;
        }
        LOGGER.warn("Восстановление escrow {} не удалось: {}", ref, rr.status());
        auction.forceOrderManualReview(order.orderId(), "escrow " + rr.status() + ": " + ref);
        return BackingResult.MANUAL_REVIEW;
    }
}

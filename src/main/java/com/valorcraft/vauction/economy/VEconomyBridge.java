package com.valorcraft.vauction.economy;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Прямой мост к публичному API VEconomy (перенесено из Exchange-скелета).
 * Используются ТОЛЬКО {@code EconomyCore.isStarted()/api()/escrow()} и api-типы;
 * внутренности VEconomy не трогаются.
 * <p>
 * Все суммы — в минимальных единицах валюты VEconomy. Каждая операция пишет причину
 * вида {@code "VAuction: ..."} в журнал транзакций и передаёт idempotencyKey —
 * защита от двойных списаний при повторе события.
 */
public final class VEconomyBridge {

    public static final String REASON_PREFIX = "VAuction: ";

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    private VEconomyBridge() {}

    public static boolean isAvailable() {
        try {
            return EconomyCore.isStarted()
                    && EconomyCore.api() != null
                    && EconomyCore.escrow() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Кинуть IllegalStateException, если VEconomy недоступен (деградация запрещена). */
    public static void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("VEconomy недоступен: аукцион не может работать");
        }
    }

    public static long getBalance(UUID playerId) {
        requireAvailable();
        return EconomyCore.api().getBalance(playerId);
    }

    public static boolean has(UUID playerId, long amount) {
        requireAvailable();
        return amount >= 0 && EconomyCore.api().has(playerId, amount);
    }

    public static String reason(String detail) {
        return REASON_PREFIX + detail;
    }

    private static TransactionContext context(UUID actor, String reason, @Nullable String idempotencyKey) {
        return idempotencyKey == null
                ? TransactionContext.of(TransactionType.PLUGIN_OPERATION, actor, reason)
                : TransactionContext.of(TransactionType.PLUGIN_OPERATION, actor, reason, idempotencyKey);
    }

    /** Списание с баланса; {@code false} при недостатке/замороженном аккаунте/повторе без изменений. */
    public static boolean withdraw(UUID playerId, long amount, String reason, @Nullable String idempotencyKey) {
        requireAvailable();
        if (amount <= 0) {
            return false;
        }
        TransactionResult result = EconomyCore.api().withdraw(playerId, amount,
                context(playerId, reason, idempotencyKey));
        if (!result.isSuccess() && result.status() != TransactionResult.Status.NO_CHANGES) {
            LOGGER.warn("VE: списание {} у {} отказано: {}", amount, playerId, result.status());
            return false;
        }
        return true;
    }

    /** Зачисление на баланс (офлайн-получатели поддерживаются — аккаунт создаётся). */
    public static boolean deposit(UUID playerId, long amount, String reason, @Nullable String idempotencyKey) {
        requireAvailable();
        if (amount <= 0) {
            return false;
        }
        TransactionResult result = EconomyCore.api().deposit(playerId, amount,
                context(playerId, reason, idempotencyKey));
        if (!result.isSuccess() && result.status() != TransactionResult.Status.NO_CHANGES) {
            LOGGER.warn("VE: начисление {} игроку {} не удалось: {}", amount, playerId, result.status());
            return false;
        }
        return true;
    }

    /** Заморозить средства у владельца под reference (резервирование в эскроу VEconomy). */
    public static boolean freezeFunds(UUID ownerId, long amount, String reference, String reason,
                                      @Nullable String idempotencyKey) {
        requireAvailable();
        if (amount <= 0 || reference == null || reference.isBlank()) {
            return false;
        }
        EscrowResult result = EconomyCore.escrow().reserveMoney(ownerId, amount, reference,
                context(ownerId, reason, idempotencyKey));
        if (!result.isSuccess()) {
            LOGGER.warn("VE: заморозка {}/{} у {} не удалась: {}", amount, reference, ownerId, result.status());
            return false;
        }
        return true;
    }

    /** Выдать замороженные средства получателю (полная сумма по reference). */
    public static boolean freezePayOut(String reference, UUID recipientId, String reason,
                                       @Nullable String idempotencyKey) {
        requireAvailable();
        EscrowResult result = EconomyCore.escrow().captureMoney(reference, recipientId,
                context(recipientId, reason, idempotencyKey));
        if (!result.isSuccess()) {
            LOGGER.warn("VE: выплата заморозки {} игроку {} не удалась: {}", reference, recipientId, result.status());
            return false;
        }
        return true;
    }

    /** Вернуть замороженные средства владельцу (полный release по reference). */
    public static boolean unfreezeRefund(String reference, String reason, @Nullable String idempotencyKey) {
        requireAvailable();
        EscrowResult result = EconomyCore.escrow().releaseMoney(reference,
                context(UUID.randomUUID(), reason, idempotencyKey));
        if (!result.isSuccess()) {
            LOGGER.warn("VE: возврат заморозки {} не удался: {}", reference, result.status());
            return false;
        }
        return true;
    }

    /** Балансы после операции (для логов), или пустая карта при неуспехе. */
    public static Map<String, Long> afterBalances(TransactionResult result) {
        if (result == null || !result.isSuccess()) {
            return Map.of();
        }
        return Map.of(
                "source", result.sourceBalanceAfter(),
                "target", result.targetBalanceAfter());
    }
}
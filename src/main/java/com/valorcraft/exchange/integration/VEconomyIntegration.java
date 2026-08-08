package com.valorcraft.exchange.integration;

import com.valorcraft.exchange.ExchangeMod;
import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.api.EconomyApi;
import com.valorcraft.veconomy.api.EscrowApi;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Прямой мост к VEconomy (никаких абстракций: работаем с {@link EconomyApi} и
 * {@link EscrowApi} напрямую).
 * <p>
 * Все суммы — в минимальных единицах валюты (minor units) VEconomy. Каждая операция
 * передаёт причину вида {@code "Биржа: ..."} и пишется в журнал транзакций VEconomy;
 * для идемпотентности используется {@code idempotencyKey} — защита от двойных
 * списаний при повторе пакета/события.
 */
public final class VEconomyIntegration {

    /** Включена ли заморозка средств через нативное эскроу VEconomy. */
    public static final boolean NATIVE_FREEZE_SUPPORTED = true;

    private VEconomyIntegration() {}

    /** Проверка: ядро VEconomy инициализировано и API доступны. */
    public static boolean isAvailable() {
        return EconomyCore.isStarted() && EconomyCore.api() != null && EconomyCore.escrow() != null;
    }

    /** Кинуть IllegalStateException, если VEconomy недоступен (деградация запрещена). */
    public static void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("VEconomy недоступен: серверная биржа не может работать");
        }
    }

    /** Текущий баланс игрока (minor units). */
    public static long getBalance(UUID playerId) {
        requireAvailable();
        return EconomyCore.api().getBalance(playerId);
    }

    /** Хватает ли средств (minor units). */
    public static boolean has(UUID playerId, long amount) {
        requireAvailable();
        return amount >= 0 && EconomyCore.api().has(playerId, amount);
    }

    // ------------------------------------------------------------------ транзакции

    private static TransactionContext context(UUID actor, String reason, @Nullable String idempotencyKey) {
        return idempotencyKey == null
                ? TransactionContext.of(TransactionType.PLUGIN_OPERATION, actor, reason)
                : TransactionContext.of(TransactionType.PLUGIN_OPERATION, actor, reason, idempotencyKey);
    }

    /** Списание с баланса. {@code false} при недостатке/замороженном аккаунте. */
    public static boolean withdraw(UUID playerId, long amount, String reason, @Nullable String idempotencyKey) {
        requireAvailable();
        if (amount <= 0) {
            return false;
        }
        TransactionResult result = EconomyCore.api().withdraw(playerId, amount,
                context(playerId, reason, idempotencyKey));
        if (!result.isSuccess() && result.status() != TransactionResult.Status.NO_CHANGES) {
            ExchangeMod.LOGGER.warn("VEconomy: списание {} у {} отказано: {}", amount, playerId, result.status());
            return false;
        }
        return true;
    }

    /** Зачисление на баланс (создаёт аккаунт, если его нет — офлайн-получатели поддерживаются). */
    public static boolean deposit(UUID playerId, long amount, String reason, @Nullable String idempotencyKey) {
        requireAvailable();
        if (amount <= 0) {
            return false;
        }
        TransactionResult result = EconomyCore.api().deposit(playerId, amount,
                context(playerId, reason, idempotencyKey));
        if (!result.isSuccess() && result.status() != TransactionResult.Status.NO_CHANGES) {
            ExchangeMod.LOGGER.warn("VE: начисление {} игроку {} не удалось: {}", amount, playerId, result.status());
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ заморозка (нативное эскроу VEconomy)

    /**
     * Заморозить средства у владельца под reference. Аналог freezeFunds из контракта биржи:
     * деньги списываются с баланса и удерживаются VEconomy до capture/release.
     */
    public static boolean freezeFunds(UUID ownerId, long amount, String reference, String reason,
                                      @Nullable String idempotencyKey) {
        requireAvailable();
        if (amount <= 0 || reference == null || reference.isBlank()) {
            return false;
        }
        EscrowResult result = EconomyCore.escrow().reserveMoney(ownerId, amount, reference,
                context(ownerId, reason, idempotencyKey));
        if (!result.isSuccess()) {
            ExchangeMod.LOGGER.warn("VE: заморозка {}/{} у {} не удалась: {}", amount, reference,
                    ownerId, result.status());
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
            ExchangeMod.LOGGER.warn("VE: выплата заморозки {} игроку {} не удалась: {}", reference,
                    recipientId, result.status());
            return false;
        }
        return true;
    }

    /** Вернуть замороженные средства владельцу. */
    public static boolean unfreezeRefund(String reference, String reason, @Nullable String idempotencyKey) {
        requireAvailable();
        EscrowResult result = EconomyCore.escrow().releaseMoney(reference,
                context(UUID.randomUUID(), reason, idempotencyKey));
        if (!result.isSuccess()) {
            ExchangeMod.LOGGER.warn("VE: возврат заморозки {} не удался: {}", reference, result.status());
            return false;
        }
        return true;
    }

    /** Балансы после операции (для логов/оптимистических проверок), или null если не отличается. */
    public static Map<String, Long> afterBalances(TransactionResult result) {
        if (result == null || !result.isSuccess()) {
            return Map.of();
        }
        return Map.of(
                "source", result.sourceBalanceAfter(),
                "target", result.targetBalanceAfter());
    }
}
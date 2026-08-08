package com.valorcraft.vauction.economy;

import java.util.List;
import java.util.UUID;

/**
 * Порты денежного слоя для аукциона. Бизнес-логика зависит только от этого
 * интерфейса (и от наших record'ов), никогда — от VEconomy напрямую: это
 * позволяет тестировать матчинг/escrow-сагу на заглушке без экономики.
 * <p>
 * Контракт идемпотентности: все операции резервирования/расчёта при повторе
 * с теми же параметрами возвращают {@code ALREADY_*} и не меняют состояние.
 * Ошибки денежного слоя НЕ маскируются (DB error ≠ «записи нет»).
 */
public interface EconomyGateway {

    /** Готова ли экономика (запущена). */
    boolean isAvailable();

    /** Баланс игрока. */
    long getBalance(UUID playerId);

    /** {@code balance >= amount}. */
    boolean has(UUID playerId, long amount);

    /**
     * Безусловное списание (админ-возвраты, донат-оплата).
     * @return false — средств не хватило или экономика недоступна.
     */
    boolean withdraw(UUID playerId, long amount, String reason, String idempotencyKey);

    /** Безусловное начисление. */
    boolean deposit(UUID playerId, long amount, String reason, String idempotencyKey);

    // ------------------------------------------------------------------ escrow

    /** Зарезервировать {@code amount} у владельца под referenceId (идемпотентно). */
    ReserveResult reserve(UUID ownerId, long amount, String referenceId,
                          String reason, String idempotencyKey);

    /**
     * Атомарно распределить зарезервированные средства (all-or-nothing).
     * Сумма кредитов обязана равняться зарезервированной сумме.
     */
    SettleResult settle(String referenceId, List<Credit> credits,
                        String reason, String idempotencyKey);

    /** Settle an old escrow and create the next BUY epoch in one economy transaction. */
    SettleResult settleAndRollover(String oldReferenceId, List<Credit> credits,
                                   String nextReferenceId, long remainderAmount,
                                   String reason, String idempotencyKey);

    /** Вернуть зарезервированные средства владельцу (идемпотентно). */
    ReleaseResult release(String referenceId, String reason, String idempotencyKey);

    /** Снимок эскроу-записи (для recovery). */
    LookupResult find(String referenceId);

    /** Системная казна (комиссии аукциона). */
    UUID treasury();

    // ---------------------------------------------------------------- типы

    /** Один получатель при расчёте (зеркалит {@code EscrowCredit} VEconomy). */
    record Credit(UUID recipientId, long amount, String role) {
        public Credit {
            if (recipientId == null) {
                throw new IllegalArgumentException("recipientId required");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be > 0");
            }
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("role required");
            }
        }
    }

    enum ReserveStatus { SUCCESS, ALREADY_RESERVED, INSUFFICIENT_FUNDS, CONFLICT, FAILED }

    record ReserveResult(ReserveStatus status, long reservedAmount, String referenceId) {
        public boolean isSuccessOrIdempotent() {
            return status == ReserveStatus.SUCCESS || status == ReserveStatus.ALREADY_RESERVED;
        }
    }

    enum SettleStatus { SUCCESS, ALREADY_SETTLED, CONFLICT, NOT_FOUND, FAILED }

    record SettleResult(SettleStatus status, long reservedAmount, String referenceId) {
        public boolean isSuccessOrIdempotent() {
            return status == SettleStatus.SUCCESS || status == SettleStatus.ALREADY_SETTLED;
        }
    }

    enum ReleaseStatus { SUCCESS, ALREADY_RELEASED, CONFLICT, NOT_FOUND, FAILED }

    record ReleaseResult(ReleaseStatus status, String referenceId) {
        public boolean isSuccessOrIdempotent() {
            return status == ReleaseStatus.SUCCESS || status == ReleaseStatus.ALREADY_RELEASED;
        }
    }

    enum LookupStatus { FOUND, NOT_FOUND, FAILED }

    enum HoldingState { RESERVED, CAPTURED, RELEASED }

    /** Снимок эскроу-записи из экономики. */
    record Holding(UUID ownerId, long amount, HoldingState state, List<Credit> settledCredits) {
        public Holding {
            settledCredits = settledCredits == null ? List.of() : List.copyOf(settledCredits);
        }
    }

    record LookupResult(LookupStatus status, Holding holding) {
        public static LookupResult notFound() {
            return new LookupResult(LookupStatus.NOT_FOUND, null);
        }

        public static LookupResult failed() {
            return new LookupResult(LookupStatus.FAILED, null);
        }
    }
}

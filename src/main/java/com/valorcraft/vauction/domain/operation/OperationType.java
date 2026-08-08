package com.valorcraft.vauction.domain.operation;

/**
 * Типы торговых операций (журнал auction_operation_log).
 * <p>
 * Синхронизирован с CHECK-constraint'ом миграции V003: НИКОГДА не добавляйте
 * значение без правки списка в V003__unified_market.sql, иначе INSERT упадёт.
 * Старые legacy-значения сохранены для чтения строк старых схем.
 */
public enum OperationType {
    // ---- НОВЫЙ единый рынок ----
    CREATE_SELL_ORDER,
    CREATE_BUY_ORDER,
    EXECUTE_FILL,
    CANCEL_ORDER,
    CLAIM_MAIL,
    RECOVERY,
    ADMIN_CANCEL,
    EXPIRE,
    MIGRATION,
    // ---- legacy-значения (чтение старых строк) ----
    CREATE_LISTING,
    PURCHASE,
    CANCEL,
    CLAIM
}
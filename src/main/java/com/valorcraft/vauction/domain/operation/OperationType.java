package com.valorcraft.vauction.domain.operation;

/**
 * Типы торговых операций (журнал auction_operation_log).
 */
public enum OperationType {
    CREATE_LISTING,
    CREATE_BUY_ORDER,
    BUY_FROM_LISTING,
    FULFILL_BUY_ORDER,
    CANCEL_LISTING,
    CANCEL_BUY_ORDER,
    CLAIM_MAIL,
    EXPIRE,
    ADMIN_CANCEL,
    RECOVERY
}
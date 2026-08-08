package com.valorcraft.vauction.domain.operation;

/**
 * Фазы операции (жизненный цикл внутри транзакции).
 */
public enum OperationPhase {
    BEGIN,
    ITEM_LOCK,
    ESCROW_RESERVE,
    ESCROW_SETTLE,
    DELIVERY,
    COMPLETE
}
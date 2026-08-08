package com.valorcraft.vauction.domain.operation;

/**
 * Статусы операции для автоматического восстановления:
 * RUNNING → COMPLETED | FAILED | COMPENSATING → MANUAL_REVIEW.
 */
public enum OperationStatus {
    RUNNING,
    COMPLETED,
    COMPENSATING,
    FAILED,
    MANUAL_REVIEW
}
package com.valorcraft.vauction.application;

import com.valorcraft.vauction.domain.operation.AuctionOperation;
import com.valorcraft.vauction.persistence.DatabaseManager;
import com.valorcraft.vauction.persistence.OperationRepository;

import java.sql.Connection;

/**
 * Помощник записи операций в журнал auction_operation_log.
 * Операция создаётся ДО мутации состояния и завершается в той же транзакции,
 * чтобы «лог + действие» были атомарны.
 */
public final class OperationRecorder {

    private final DatabaseManager database;
    private final OperationRepository operations;

    public OperationRecorder(DatabaseManager database, OperationRepository operations) {
        this.database = database;
        this.operations = operations;
    }

    /** Начать операцию внутри переданной транзакции. */
    public void begin(Connection connection, AuctionOperation operation) {
        operations.insert(connection, operation);
    }

    /** Завершить операцию успехом (RUNNING → COMPLETED) внутри той же транзакции. */
    public void complete(Connection connection, AuctionOperation running) {
        operations.applyRetry(connection, running.operationId(),
                running.attemptCount(), running.toCompleted(System.currentTimeMillis()));
    }

    /** Отметить операцию как проваленную/требующую повтора. */
    public void fail(Connection connection, AuctionOperation running, String error, Long retryAt) {
        operations.applyRetry(connection, running.operationId(),
                running.attemptCount(), running.toFailed(error, retryAt, System.currentTimeMillis()));
    }
}
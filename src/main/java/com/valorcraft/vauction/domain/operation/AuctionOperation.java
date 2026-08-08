package com.valorcraft.vauction.domain.operation;

import java.util.Objects;
import java.util.UUID;

/**
 * Запись журнала торговой операции (auction_operation_log). Иммутабельна.
 * Служит фундаментом будущего recovery: idempotency_key + attempt_count + статусы.
 */
public record AuctionOperation(
        String operationId,
        Long listingId,
        OperationType operationType,
        OperationPhase phase,
        OperationStatus status,
        UUID actorUuid,
        UUID targetUuid,
        String idempotencyKey,
        String payloadJson,
        int attemptCount,
        String lastError,
        Long nextRetryAt,
        long createdAt,
        long updatedAt
) {

    public AuctionOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (operationId.isBlank() || operationId.length() > 64) {
            throw new IllegalArgumentException("operationId must be 1..64 chars");
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 191) {
            throw new IllegalArgumentException("idempotencyKey must be 1..191 chars");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must be >= 0");
        }
    }

    /* ------------------------------- transitions ------------------------------- */

    public AuctionOperation toCompleted(long now) {
        return new AuctionOperation(operationId, listingId, operationType, OperationPhase.COMPLETE,
                OperationStatus.COMPLETED, actorUuid, targetUuid, idempotencyKey, payloadJson,
                attemptCount, lastError, null, createdAt, now);
    }

    public AuctionOperation toFailed(String error, Long retryAt, long now) {
        return new AuctionOperation(operationId, listingId, operationType, phase, OperationStatus.FAILED,
                actorUuid, targetUuid, idempotencyKey, payloadJson, attemptCount + 1,
                error, retryAt, createdAt, now);
    }

    public AuctionOperation toCompensating(String error, long now) {
        return new AuctionOperation(operationId, listingId, operationType, phase,
                OperationStatus.COMPENSATING, actorUuid, targetUuid, idempotencyKey, payloadJson,
                attemptCount + 1, error, null, createdAt, now);
    }

    public AuctionOperation toManualReview(String error, long now) {
        return new AuctionOperation(operationId, listingId, operationType, phase,
                OperationStatus.MANUAL_REVIEW, actorUuid, targetUuid, idempotencyKey, payloadJson,
                attemptCount, error, null, createdAt, now);
    }

    public static Builder newOperation(OperationType type, String idempotencyKey, long now) {
        return new Builder(type, idempotencyKey, now);
    }

    public static final class Builder {
        private final OperationType operationType;
        private final String idempotencyKey;
        private final long createdAt;
        private String operationId;
        private Long listingId;
        private OperationPhase phase = OperationPhase.BEGIN;
        private UUID actorUuid;
        private UUID targetUuid;
        private String payloadJson;

        private Builder(OperationType operationType, String idempotencyKey, long createdAt) {
            this.operationType = operationType;
            this.idempotencyKey = idempotencyKey;
            this.createdAt = createdAt;
        }

        public Builder operationId(String id) { this.operationId = id; return this; }

        public Builder listingId(long id) { this.listingId = id; return this; }

        public Builder phase(OperationPhase phase) { this.phase = phase; return this; }

        public Builder actor(UUID actor) { this.actorUuid = actor; return this; }

        public Builder target(UUID target) { this.targetUuid = target; return this; }

        public Builder payload(String json) { this.payloadJson = json; return this; }

        public AuctionOperation build() {
            return new AuctionOperation(operationId, listingId, operationType, phase,
                    OperationStatus.RUNNING, actorUuid, targetUuid, idempotencyKey,
                    payloadJson, 0, null, null, createdAt, createdAt);
        }
    }
}
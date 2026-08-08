package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.operation.AuctionOperation;
import com.valorcraft.vauction.domain.operation.OperationPhase;
import com.valorcraft.vauction.domain.operation.OperationStatus;
import com.valorcraft.vauction.domain.operation.OperationType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий журнала операций (auction_operation_log).
 * idempotency_key UNIQUE — сердце идемпотентности повторных попыток.
 */
public final class OperationRepository {

    private static final String COLUMNS = "operation_id, listing_id, operation_type, phase, status, "
            + "actor_uuid, target_uuid, idempotency_key, payload_json, attempt_count, last_error, "
            + "next_retry_at, created_at, updated_at";

    public long insert(Connection c, AuctionOperation operation) {
        String sql = "INSERT INTO auction_operation_log (operation_id, listing_id, operation_type, phase, "
                + "status, actor_uuid, target_uuid, idempotency_key, payload_json, attempt_count, "
                + "last_error, next_retry_at, created_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, operation.operationId());
            if (operation.listingId() == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, operation.listingId());
            }
            ps.setString(3, operation.operationType().name());
            ps.setString(4, operation.phase().name());
            ps.setString(5, operation.status().name());
            ps.setString(6, operation.actorUuid() == null ? null : operation.actorUuid().toString());
            ps.setString(7, operation.targetUuid() == null ? null : operation.targetUuid().toString());
            ps.setString(8, operation.idempotencyKey());
            ps.setString(9, operation.payloadJson());
            ps.setInt(10, operation.attemptCount());
            ps.setString(11, operation.lastError());
            setLongNullable(ps, 12, operation.nextRetryAt());
            ps.setLong(13, operation.createdAt());
            ps.setLong(14, operation.updatedAt());
            ps.executeUpdate();
            return 1;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new DatabaseException("operation with idempotency_key already exists: "
                        + operation.idempotencyKey(), e);
            }
            throw new DatabaseException("insert operation failed", e);
        }
    }

    public Optional<AuctionOperation> findById(Connection c, String operationId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_operation_log WHERE operation_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find operation failed", e);
        }
    }

    /** Оптимистичное обновление: CAS по attempt_count (у операции нет версии — повтор защищён этим счётчиком). */
    public boolean applyRetry(Connection c, String operationId, int expectedAttempts, AuctionOperation updated) {
        String sql = "UPDATE auction_operation_log SET phase=?, status=?, payload_json=?, attempt_count=?, "
                + "last_error=?, next_retry_at=?, updated_at=? "
                + "WHERE operation_id = ? AND attempt_count = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, updated.phase().name());
            ps.setString(2, updated.status().name());
            ps.setString(3, updated.payloadJson());
            ps.setInt(4, updated.attemptCount());
            ps.setString(5, updated.lastError());
            setLongNullable(ps, 6, updated.nextRetryAt());
            ps.setLong(7, updated.updatedAt());
            ps.setString(8, operationId);
            ps.setInt(9, expectedAttempts);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("update operation failed: " + operationId, e);
        }
    }

    /* ------------------------------- mapping ------------------------------- */

    private static AuctionOperation map(ResultSet rs) throws SQLException {
        return new AuctionOperation(
                rs.getString("operation_id"),
                nullableLong(rs, "listing_id"),
                OperationType.valueOf(rs.getString("operation_type")),
                OperationPhase.valueOf(rs.getString("phase")),
                OperationStatus.valueOf(rs.getString("status")),
                uuidOrNull(rs, "actor_uuid"),
                uuidOrNull(rs, "target_uuid"),
                rs.getString("idempotency_key"),
                rs.getString("payload_json"),
                rs.getInt("attempt_count"),
                rs.getString("last_error"),
                nullableLong(rs, "next_retry_at"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private static void setLongNullable(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static UUID uuidOrNull(ResultSet rs, String column) throws SQLException {
        String s = rs.getString(column);
        return s == null ? null : UUID.fromString(s);
    }
}
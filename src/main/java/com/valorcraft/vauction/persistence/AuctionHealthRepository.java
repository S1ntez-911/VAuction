package com.valorcraft.vauction.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** One bounded diagnostic query; it never loads auction rows or item blobs. */
public final class AuctionHealthRepository {
    public record Snapshot(int activeOrders, int processingOrders, int manualReviewOrders,
                           int pendingTrades, int manualReviewTrades, int claimableDeliveries,
                           int claimingDeliveries, int failedDeliveries, int runningOperations,
                           int manualReviewOperations, int matchingQueue) {
        public int recoveryBacklog() {
            return processingOrders + pendingTrades + claimingDeliveries;
        }

        public int attentionRequired() {
            return manualReviewOrders + manualReviewTrades + failedDeliveries + manualReviewOperations;
        }
    }

    public Snapshot read(Connection connection) {
        String sql = "SELECT "
                + "(SELECT COUNT(*) FROM auction_orders WHERE status='ACTIVE') active_orders,"
                + "(SELECT COUNT(*) FROM auction_orders WHERE processing_state<>'NONE') processing_orders,"
                + "(SELECT COUNT(*) FROM auction_orders WHERE status='MANUAL_REVIEW') manual_orders,"
                + "(SELECT COUNT(*) FROM auction_trades WHERE state='PENDING') pending_trades,"
                + "(SELECT COUNT(*) FROM auction_trades WHERE state='MANUAL_REVIEW') manual_trades,"
                + "(SELECT COUNT(*) FROM auction_deliveries WHERE state='CLAIMABLE') claimable_deliveries,"
                + "(SELECT COUNT(*) FROM auction_deliveries WHERE state='CLAIMING') claiming_deliveries,"
                + "(SELECT COUNT(*) FROM auction_deliveries WHERE state='FAILED') failed_deliveries,"
                + "(SELECT COUNT(*) FROM auction_operation_log WHERE status IN ('RUNNING','COMPENSATING')) running_operations,"
                + "(SELECT COUNT(*) FROM auction_operation_log WHERE status='MANUAL_REVIEW') manual_operations,"
                + "(SELECT COUNT(*) FROM auction_match_queue) matching_queue";
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new DatabaseException("auction health query returned no row");
            return new Snapshot(rs.getInt("active_orders"), rs.getInt("processing_orders"),
                    rs.getInt("manual_orders"), rs.getInt("pending_trades"), rs.getInt("manual_trades"),
                    rs.getInt("claimable_deliveries"), rs.getInt("claiming_deliveries"),
                    rs.getInt("failed_deliveries"), rs.getInt("running_operations"),
                    rs.getInt("manual_operations"), rs.getInt("matching_queue"));
        } catch (SQLException e) {
            throw new DatabaseException("auction health query failed", e);
        }
    }
}

package com.valorcraft.vauction.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Idempotent, transactional SQLite schema migration. */
final class AuctionSchema {
    private AuctionSchema() {}

    static void migrate(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            migrateTransaction(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void migrateTransaction(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS auction_schema (version INTEGER NOT NULL)");
            statement.execute("INSERT INTO auction_schema(version) SELECT 5 WHERE NOT EXISTS (SELECT 1 FROM auction_schema)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS auction_listings (
                      id TEXT PRIMARY KEY, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL,
                      item_nbt TEXT NOT NULL, price_minor INTEGER NOT NULL CHECK(price_minor > 0),
                      created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                      state TEXT NOT NULL CHECK(state IN ('ACTIVE','PENDING_PAYMENT','SOLD','CANCELLED','EXPIRED','CLAIMED')),
                      buyer_uuid TEXT, buyer_name TEXT, sold_at INTEGER NOT NULL DEFAULT 0,
                      escrow_reference TEXT, updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS auction_notifications (
                      player_uuid TEXT NOT NULL, notification_id TEXT NOT NULL, message TEXT NOT NULL,
                      created_at INTEGER NOT NULL, PRIMARY KEY(player_uuid, notification_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS auction_quarantine (
                      quarantine_id INTEGER PRIMARY KEY AUTOINCREMENT, source_rowid INTEGER,
                      listing_id TEXT, item_nbt TEXT, reason TEXT NOT NULL, quarantined_at INTEGER NOT NULL
                    )
                    """);
        }

        // Old installations lack these columns. They must exist before dependent indexes are created.
        if (!hasColumn(connection, "auction_listings", "updated_at")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE auction_listings ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
                statement.execute("UPDATE auction_listings SET updated_at = created_at WHERE updated_at = 0");
            }
        }
        if (!hasColumn(connection, "auction_listings", "buyer_name")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE auction_listings ADD COLUMN buyer_name TEXT");
            }
        }
        if (!hasColumn(connection, "auction_listings", "sold_at")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE auction_listings ADD COLUMN sold_at INTEGER NOT NULL DEFAULT 0");
            }
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_state_created ON auction_listings(state, created_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_seller_state ON auction_listings(seller_uuid, state)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_expiry ON auction_listings(state, expires_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_buyer_state ON auction_listings(buyer_uuid, state)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_seller_sold ON auction_listings(seller_uuid, sold_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_state_updated ON auction_listings(state, updated_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_auction_notifications_player ON auction_notifications(player_uuid, created_at)");
            statement.execute("UPDATE auction_schema SET version = 5");
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equalsIgnoreCase(result.getString("name"))) return true;
            return false;
        }
    }
}

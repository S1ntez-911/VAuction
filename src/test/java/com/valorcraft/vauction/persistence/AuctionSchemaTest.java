package com.valorcraft.vauction.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class AuctionSchemaTest {
    @Test
    void migratesOldDatabaseWithoutLosingListingsAndCanRunTwice() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auction_schema(version INTEGER NOT NULL)");
            statement.execute("INSERT INTO auction_schema VALUES (1)");
            statement.execute("""
                    CREATE TABLE auction_listings (
                      id TEXT PRIMARY KEY, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL,
                      item_nbt TEXT NOT NULL, price_minor INTEGER NOT NULL, created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL, state TEXT NOT NULL, buyer_uuid TEXT, escrow_reference TEXT
                    )
                    """);
            statement.execute("INSERT INTO auction_listings VALUES ('lot-1','seller-1','Seller','{}',450,100,200,'ACTIVE',NULL,NULL)");

            AuctionSchema.migrate(connection);
            AuctionSchema.migrate(connection);

            Set<String> columns = new HashSet<>();
            try (ResultSet result = statement.executeQuery("PRAGMA table_info(auction_listings)")) {
                while (result.next()) columns.add(result.getString("name"));
            }
            assertTrue(columns.containsAll(Set.of("updated_at", "buyer_name", "sold_at")));
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM auction_listings WHERE id='lot-1' AND price_minor=450"));
            assertEquals(5, scalar(statement, "SELECT version FROM auction_schema"));
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_auction_seller_sold'"));
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='auction_quarantine'"));
        }
    }

    @Test
    void migratesPartiallyUpgradedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auction_schema(version INTEGER NOT NULL)");
            statement.execute("INSERT INTO auction_schema VALUES (3)");
            statement.execute("""
                    CREATE TABLE auction_listings (
                      id TEXT PRIMARY KEY, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL,
                      item_nbt TEXT NOT NULL, price_minor INTEGER NOT NULL, created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL, state TEXT NOT NULL, buyer_uuid TEXT,
                      buyer_name TEXT, escrow_reference TEXT, updated_at INTEGER NOT NULL
                    )
                    """);

            AuctionSchema.migrate(connection);

            assertEquals(5, scalar(statement, "SELECT version FROM auction_schema"));
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM pragma_table_info('auction_listings') WHERE name='sold_at'"));
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_auction_seller_sold'"));
        }
    }

    @Test
    void createsFreshSchemaWithSafetyConstraints() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            AuctionSchema.migrate(connection);
            assertEquals(5, scalar(statement, "SELECT version FROM auction_schema"));
            assertThrows(Exception.class, () -> statement.execute("""
                    INSERT INTO auction_listings
                    (id,seller_uuid,seller_name,item_nbt,price_minor,created_at,expires_at,state,updated_at)
                    VALUES ('bad','seller','Seller','{}',0,1,2,'ACTIVE',1)
                    """));
        }
    }

    @Test
    void rollsBackPartiallyAppliedMigrationOnFailure() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auction_schema(version INTEGER NOT NULL)");
            statement.execute("INSERT INTO auction_schema VALUES (1)");
            statement.execute("""
                    CREATE TABLE auction_listings (
                      id TEXT PRIMARY KEY, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL,
                      item_nbt TEXT NOT NULL, price_minor INTEGER NOT NULL, created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL, state TEXT NOT NULL, buyer_uuid TEXT, escrow_reference TEXT
                    )
                    """);
            // Deliberately incompatible existing table makes the final notification index fail.
            statement.execute("CREATE TABLE auction_notifications(player_uuid TEXT)");

            assertThrows(Exception.class, () -> AuctionSchema.migrate(connection));
            assertEquals(0, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('auction_listings') WHERE name='sold_at'"));
            assertEquals(1, scalar(statement, "SELECT version FROM auction_schema"));
        }
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}

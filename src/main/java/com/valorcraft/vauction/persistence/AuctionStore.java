package com.valorcraft.vauction.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.valorcraft.vauction.VAuctionMod;
import com.valorcraft.vauction.config.AuctionConfig;
import com.valorcraft.vauction.model.AuctionListing;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuctionStore implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String UPSERT_LISTING = """
            INSERT INTO auction_listings
            (id, seller_uuid, seller_name, item_nbt, price_minor, created_at, expires_at, state,
             buyer_uuid, buyer_name, sold_at, escrow_reference, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              seller_uuid=excluded.seller_uuid,
              seller_name=excluded.seller_name,
              item_nbt=excluded.item_nbt,
              price_minor=excluded.price_minor,
              created_at=excluded.created_at,
              expires_at=excluded.expires_at,
              state=excluded.state,
              buyer_uuid=excluded.buyer_uuid,
              buyer_name=excluded.buyer_name,
              sold_at=excluded.sold_at,
              escrow_reference=excluded.escrow_reference,
              updated_at=excluded.updated_at
            """;

    private final Path directory = FMLPaths.CONFIGDIR.get().resolve("VMods").resolve("VAuction");
    private final Path databaseFile = directory.resolve("auction.db");
    private final Path jsonFile = directory.resolve("listings.json");
    private final Map<UUID, AuctionListing> listings = new LinkedHashMap<>();
    private final Map<AuctionListing.State, LinkedHashSet<UUID>> stateIndex =
            new EnumMap<>(AuctionListing.State.class);
    private final Map<UUID, LinkedHashSet<UUID>> sellerIndex = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> buyerIndex = new LinkedHashMap<>();
    private final Map<UUID, UUID> indexedBuyer = new LinkedHashMap<>();
    private Connection connection;
    private long transactionCount;
    private long transactionNanos;
    private long maxTransactionNanos;

    public synchronized void load() {
        close();
        listings.clear();
        clearIndexes();
        transactionCount = transactionNanos = maxTransactionNanos = 0L;
        try {
            Files.createDirectories(directory);
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            configureDatabase();
            migrateSchema();
            migrateJsonIfNeeded();
            loadListings();
            VAuctionMod.LOGGER.info("Загружено {} лотов из {}", listings.size(), databaseFile);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC не найден. На сервер нужен полный VEconomy *-all.jar", e);
        } catch (IOException | SQLException | CommandSyntaxException | RuntimeException e) {
            close();
            throw new IllegalStateException("Не удалось открыть базу VAuction " + databaseFile, e);
        }
    }

    public synchronized Collection<AuctionListing> all() {
        ensureOpen();
        return new ArrayList<>(listings.values());
    }

    public synchronized Collection<AuctionListing> byState(AuctionListing.State state) {
        ensureOpen();
        return resolveIds(stateIndex.getOrDefault(state, new LinkedHashSet<>()));
    }

    public synchronized Collection<AuctionListing> bySeller(UUID sellerId) {
        ensureOpen();
        return resolveIds(sellerIndex.getOrDefault(sellerId, new LinkedHashSet<>()));
    }

    public synchronized Collection<AuctionListing> byBuyer(UUID buyerId) {
        ensureOpen();
        return resolveIds(buyerIndex.getOrDefault(buyerId, new LinkedHashSet<>()));
    }

    public synchronized int size() {
        ensureOpen();
        return listings.size();
    }

    public synchronized TransactionStats transactionStats() {
        return new TransactionStats(transactionCount, transactionNanos, maxTransactionNanos);
    }

    public synchronized AuctionListing get(UUID id) {
        ensureOpen();
        return listings.get(id);
    }

    public synchronized void put(AuctionListing listing) {
        ensureOpen();
        ListingIntegrity.validate(listing);
        inTransaction(() -> upsert(listing));
        listings.put(listing.id(), listing);
        reindex(listing);
    }

    public synchronized void update(AuctionListing listing) {
        ensureOpen();
        if (!listings.containsKey(listing.id())) throw new IllegalArgumentException("Неизвестный лот " + listing.id());
        ListingIntegrity.validate(listing);
        inTransaction(() -> upsert(listing));
        reindex(listing);
    }

    public synchronized void updateAll(Collection<AuctionListing> changed) {
        ensureOpen();
        if (changed.isEmpty()) return;
        for (AuctionListing listing : changed) ListingIntegrity.validate(listing);
        inTransaction(() -> {
            for (AuctionListing listing : changed) upsert(listing);
        });
        for (AuctionListing listing : changed) reindex(listing);
    }

    public synchronized void addNotification(UUID playerId, UUID notificationId, String message) {
        ensureOpen();
        inTransaction(() -> insertNotification(playerId, notificationId, message));
    }

    public synchronized void updateWithNotification(AuctionListing listing, UUID playerId,
                                                     UUID notificationId, String message) {
        ensureOpen();
        inTransaction(() -> {
            upsert(listing);
            insertNotification(playerId, notificationId, message);
        });
    }

    public synchronized int pruneClaimed(int retentionDays) {
        ensureOpen();
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        final int[] removed = {0};
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM auction_listings WHERE state = 'CLAIMED' AND updated_at < ?")) {
                statement.setLong(1, cutoff);
                removed[0] = statement.executeUpdate();
            }
        });
        if (removed[0] > 0) reloadCacheAfterPrune();
        return removed[0];
    }

    public synchronized List<PendingNotification> peekNotifications(UUID playerId) {
        ensureOpen();
        List<PendingNotification> messages = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT notification_id, message FROM auction_notifications
                WHERE player_uuid = ? ORDER BY created_at, notification_id
                """)) {
            select.setString(1, playerId.toString());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) messages.add(new PendingNotification(result.getString(1), result.getString(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать уведомления VAuction", e);
        }
        return List.copyOf(messages);
    }

    public synchronized void acknowledgeNotifications(UUID playerId, Collection<String> notificationIds) {
        ensureOpen();
        if (notificationIds.isEmpty()) return;
        inTransaction(() -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM auction_notifications WHERE player_uuid = ? AND notification_id = ?")) {
                for (String id : notificationIds) {
                    delete.setString(1, playerId.toString());
                    delete.setString(2, id);
                    delete.addBatch();
                }
                delete.executeBatch();
            }
        });
    }

    @Override
    public synchronized void close() {
        listings.clear();
        clearIndexes();
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            VAuctionMod.LOGGER.error("Ошибка закрытия базы VAuction", e);
        } finally {
            connection = null;
        }
    }

    private void configureDatabase() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + AuctionConfig.DATABASE_BUSY_TIMEOUT_MS.get());
            statement.execute("PRAGMA wal_autocheckpoint=1000");
        }
    }

    private void migrateSchema() throws SQLException {
        AuctionSchema.migrate(connection);
    }

    private void loadListings() throws SQLException {
        List<InvalidListing> invalid = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT rowid AS source_rowid, id, seller_uuid, seller_name, item_nbt, price_minor, created_at, expires_at,
                            state, buyer_uuid, buyer_name, sold_at, escrow_reference FROM auction_listings
                     """)) {
            while (result.next()) {
                try {
                    AuctionListing listing = listingFromRow(result);
                    ListingIntegrity.validate(listing);
                    listings.put(listing.id(), listing);
                    reindex(listing);
                } catch (CommandSyntaxException | IllegalArgumentException e) {
                    invalid.add(new InvalidListing(result.getLong("source_rowid"), result.getString("id"),
                            result.getString("item_nbt"), e.getClass().getSimpleName() + ": " + e.getMessage()));
                }
            }
        }
        if (!invalid.isEmpty()) quarantine(invalid);
    }

    private void quarantine(List<InvalidListing> invalid) throws SQLException {
        inTransactionSql(() -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                         INSERT INTO auction_quarantine
                         (source_rowid, listing_id, item_nbt, reason, quarantined_at) VALUES (?, ?, ?, ?, ?)
                         """);
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM auction_listings WHERE rowid = ?")) {
                long now = System.currentTimeMillis();
                for (InvalidListing row : invalid) {
                    insert.setLong(1, row.rowId()); insert.setString(2, row.id());
                    insert.setString(3, row.itemNbt()); insert.setString(4, row.reason());
                    insert.setLong(5, now); insert.addBatch();
                    delete.setLong(1, row.rowId()); delete.addBatch();
                }
                insert.executeBatch();
                delete.executeBatch();
            }
        });
        VAuctionMod.LOGGER.error("{} повреждённых лотов перемещено в auction_quarantine; остальные лоты загружены", invalid.size());
    }

    private void migrateJsonIfNeeded() throws IOException, SQLException, CommandSyntaxException {
        Path source = Files.exists(jsonFile) ? jsonFile
                : FMLPaths.CONFIGDIR.get().resolve("vauction").resolve("listings.json");
        if (!Files.exists(source) || rowCount("auction_listings") > 0 || rowCount("auction_notifications") > 0) return;

        StoreData data = GSON.fromJson(Files.readString(source, StandardCharsets.UTF_8), StoreData.class);
        if (data == null) return;
        List<AuctionListing> importedListings = new ArrayList<>();
        if (data.listings != null) {
            for (ListingData raw : data.listings) importedListings.add(raw.toListing());
        }
        inTransactionSql(() -> {
            for (AuctionListing listing : importedListings) upsert(listing);
            if (data.notifications != null) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT OR IGNORE INTO auction_notifications
                        (player_uuid, notification_id, message, created_at) VALUES (?, ?, ?, ?)
                        """)) {
                    for (Map.Entry<String, Map<String, String>> player : data.notifications.entrySet()) {
                        for (Map.Entry<String, String> notification : player.getValue().entrySet()) {
                            insert.setString(1, player.getKey());
                            insert.setString(2, notification.getKey());
                            insert.setString(3, notification.getValue());
                            insert.setLong(4, System.currentTimeMillis());
                            insert.addBatch();
                        }
                    }
                    insert.executeBatch();
                }
            }
        });
        Path archived = source.resolveSibling(source.getFileName() + ".migrated");
        Files.move(source, archived, StandardCopyOption.REPLACE_EXISTING);
        VAuctionMod.LOGGER.info("JSON-хранилище импортировано в SQLite и сохранено как {}", archived);
    }

    private long rowCount(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private void upsert(AuctionListing listing) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_LISTING)) {
            statement.setString(1, listing.id().toString());
            statement.setString(2, listing.sellerId().toString());
            statement.setString(3, listing.sellerName());
            statement.setString(4, listing.item().save(new CompoundTag()).toString());
            statement.setLong(5, listing.price());
            statement.setLong(6, listing.createdAt());
            statement.setLong(7, listing.expiresAt());
            statement.setString(8, listing.state().name());
            statement.setString(9, listing.buyerId() == null ? null : listing.buyerId().toString());
            statement.setString(10, listing.buyerName());
            statement.setLong(11, listing.soldAt());
            statement.setString(12, listing.escrowReference());
            statement.setLong(13, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void reloadCacheAfterPrune() {
        listings.clear();
        clearIndexes();
        try {
            loadListings();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось обновить кеш VAuction после очистки истории", e);
        }
    }

    private void insertNotification(UUID playerId, UUID notificationId, String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO auction_notifications
                (player_uuid, notification_id, message, created_at) VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, notificationId.toString());
            statement.setString(3, message);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private static AuctionListing listingFromRow(ResultSet result) throws SQLException, CommandSyntaxException {
        String buyer = result.getString("buyer_uuid");
        return new AuctionListing(UUID.fromString(result.getString("id")),
                UUID.fromString(result.getString("seller_uuid")), result.getString("seller_name"),
                ItemStack.of(TagParser.parseTag(result.getString("item_nbt"))), result.getLong("price_minor"),
                result.getLong("created_at"), result.getLong("expires_at"),
                AuctionListing.State.valueOf(result.getString("state")),
                buyer == null ? null : UUID.fromString(buyer), result.getString("buyer_name"),
                result.getLong("sold_at"), result.getString("escrow_reference"));
    }

    private Collection<AuctionListing> resolveIds(Collection<UUID> ids) {
        ArrayList<AuctionListing> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            AuctionListing listing = listings.get(id);
            if (listing != null) result.add(listing);
        }
        return result;
    }

    private void reindex(AuctionListing listing) {
        UUID id = listing.id();
        for (LinkedHashSet<UUID> ids : stateIndex.values()) ids.remove(id);
        stateIndex.computeIfAbsent(listing.state(), ignored -> new LinkedHashSet<>()).add(id);
        sellerIndex.computeIfAbsent(listing.sellerId(), ignored -> new LinkedHashSet<>()).add(id);
        UUID previousBuyer = indexedBuyer.put(id, listing.buyerId());
        if (previousBuyer != null && !previousBuyer.equals(listing.buyerId())) {
            LinkedHashSet<UUID> old = buyerIndex.get(previousBuyer);
            if (old != null) { old.remove(id); if (old.isEmpty()) buyerIndex.remove(previousBuyer); }
        }
        if (listing.buyerId() != null)
            buyerIndex.computeIfAbsent(listing.buyerId(), ignored -> new LinkedHashSet<>()).add(id);
        else indexedBuyer.remove(id);
    }

    private void clearIndexes() {
        stateIndex.clear(); sellerIndex.clear(); buyerIndex.clear(); indexedBuyer.clear();
    }

    private void ensureOpen() {
        try {
            if (connection == null || connection.isClosed()) throw new IllegalStateException("База VAuction не открыта");
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось проверить базу VAuction", e);
        }
    }

    private void inTransaction(SqlAction action) {
        try {
            inTransactionSql(action);
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("Транзакция VAuction не выполнена", e);
        }
    }

    private void inTransactionSql(SqlAction action) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        long started = System.nanoTime();
        Throwable primary = null;
        connection.setAutoCommit(false);
        try {
            action.run();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            primary = e;
            try { connection.rollback(); }
            catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        } finally {
            long elapsed = System.nanoTime() - started;
            transactionCount++;
            transactionNanos += elapsed;
            maxTransactionNanos = Math.max(maxTransactionNanos, elapsed);
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException reset) {
                if (primary != null) primary.addSuppressed(reset); else throw reset;
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction { void run() throws SQLException; }

    private record StoreData(List<ListingData> listings, Map<String, Map<String, String>> notifications) {}
    private record InvalidListing(long rowId, String id, String itemNbt, String reason) {}
    public record PendingNotification(String id, String message) {}
    public record TransactionStats(long count, long totalNanos, long maxNanos) {}

    private static final class ListingData {
        String id;
        String sellerId;
        String sellerName;
        String itemNbt;
        long price;
        long createdAt;
        long expiresAt;
        String state;
        String buyerId;
        String escrowReference;

        AuctionListing toListing() throws CommandSyntaxException {
            return new AuctionListing(UUID.fromString(id), UUID.fromString(sellerId), sellerName,
                    ItemStack.of(TagParser.parseTag(itemNbt)), price, createdAt, expiresAt,
                    AuctionListing.State.valueOf(state), buyerId == null ? null : UUID.fromString(buyerId),
                    escrowReference);
        }
    }
}

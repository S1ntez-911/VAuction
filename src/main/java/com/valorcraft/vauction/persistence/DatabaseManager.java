package com.valorcraft.vauction.persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Фасад собственной БД VAuction: открытие, миграции, проверка схемы, версия схемы.
 * Репозитории получаются отдельно и работают через {@link JdbcSource}.
 * <p>
 * Архитектурная граница: это СОБСТВЕННАЯ БД аукциона; таблицы VEconomy не используются.
 * Позже для MySQL достаточно другой реализации {@link JdbcSource} и отдельного
 * семейства миграций — domain/application не меняются.
 */
public final class DatabaseManager implements AutoCloseable {

    private final JdbcSource source;
    private int schemaVersion = -1;

    public DatabaseManager(JdbcSource source) {
        this.source = source;
    }

    public static DatabaseManager openSqlite(Path databasePath) {
        Path absolutePath = databasePath.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new DatabaseException("cannot create sqlite directory: " + parent, e);
        }
        SqliteJdbcSource source = new SqliteJdbcSource("jdbc:sqlite:" + absolutePath);
        source.open();
        return new DatabaseManager(source);
    }

    public static DatabaseManager openInMemory() {
        SqliteJdbcSource source = new SqliteJdbcSource("jdbc:sqlite::memory:");
        source.open();
        return new DatabaseManager(source);
    }

    /** Открыть (если ещё закрыт), применить миграции, проверить схему. Выбрасывает исключение при сбое. */
    public synchronized void initialize() {
        source.open();
        MigrationRunner.Result result = source.query(MigrationRunner::run);
        schemaVersion = result.schemaVersion();
        source.query(SchemaVerifier::verify);
        LOGGER.info("БД VAuction готова, схема v{} (применено миграций: {})",
                result.schemaVersion(), result.appliedCount());
    }

    /** Выполнить блок в единой транзакции. */
    public <T> T inTransaction(JdbcSource.Work<T> work) {
        return source.inTransaction(work);
    }

    /** Выполнить короткую операцию чтения. */
    public <T> T query(JdbcSource.Work<T> work) {
        return source.query(work);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public JdbcSource source() {
        return source;
    }

    @Override
    public synchronized void close() {
        source.close();
    }

    private static final Logger LOGGER = LogManager.getLogger("VAuction");
}

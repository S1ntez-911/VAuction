package com.valorcraft.vauction.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * SQLite-источник соединений (СУБД по умолчанию для VAuction).
 * <p>
 * Одно синхронизированное JDBC-соединение: вызовы допускаются только с серверного
 * потока (стандартное требование серверных Forge-модов). WAL для продакшена;
 * для {@code jdbc:sqlite::memory:} (тесты) — обычный режим.
 */
public final class SqliteJdbcSource implements JdbcSource {

    private final String jdbcUrl;
    private final boolean inMemory;
    private Connection connection;
    private boolean closed = true;

    public SqliteJdbcSource(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        this.jdbcUrl = jdbcUrl;
        this.inMemory = jdbcUrl.startsWith("jdbc:sqlite::memory:");
    }

    @Override
    public synchronized void open() {
        if (!closed) {
            return;
        }
        try {
            connection = DriverManager.getConnection(jdbcUrl);
        } catch (SQLException e) {
            throw new DatabaseException("cannot open sqlite db: " + jdbcUrl, e);
        }
        configure(connection);
        closed = false;
    }

    private void configure(Connection c) {
        try (java.sql.Statement st = c.createStatement()) {
            if (!inMemory) {
                st.execute("PRAGMA journal_mode=WAL");
            }
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            throw new DatabaseException("failed to configure sqlite", e);
        }
    }

    @Override
    public synchronized <T> T query(Work<T> work) {
        assertOpen();
        try {
            connection.setAutoCommit(true);
            return work.execute(connection);
        } catch (DatabaseException e) {
            throw e;
        } catch (Exception e) {
            throw new DatabaseException("query failed", e);
        }
    }

    @Override
    public synchronized <T> T inTransaction(Work<T> work) {
        assertOpen();
        Connection conn = connection;
        boolean previousAutoCommit;
        try {
            previousAutoCommit = conn.getAutoCommit();
        } catch (SQLException e) {
            throw new DatabaseException("cannot read autocommit", e);
        }
        try {
            conn.setAutoCommit(false);
            T result = work.execute(conn);
            conn.commit();
            return result;
        } catch (DatabaseException e) {
            rollbackQuietly(conn);
            throw e;
        } catch (Exception e) {
            rollbackQuietly(conn);
            throw new DatabaseException("transaction failed", e);
        } finally {
            try {
                conn.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
                // ничего страшного: соединение уже в транзакции либо закрывается
            }
        }
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // первичная ошибка важнее
        }
    }

    private void assertOpen() {
        if (closed) {
            throw new DatabaseException("database is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DatabaseException("failed to close sqlite db", e);
        } finally {
            connection = null;
            closed = true;
        }
    }
}
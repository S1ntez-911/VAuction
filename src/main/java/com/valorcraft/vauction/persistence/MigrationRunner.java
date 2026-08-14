package com.valorcraft.vauction.persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Система schema-миграций VAuction.
 * <ul>
 *   <li>мета-таблица {@code schema_version (version, description, checksum, applied_at)};</li>
 *   <li>скрипты лежат в classpath: {@code /vauction/migrations/V###__name.sql}
 *       (список ресурсов зарегистрирован статически — надёжно работает и из jar, и из IDE);</li>
 *   <li>применяются только недостающие версии, строго по порядку; каждая миграция —
 *       в собственной транзакции (rollback при ошибке);</li>
 *   <li>идемпотентность: повторный запуск не применяет уже применённые версии;
 *       при изменении тела уже применённой миграции — предупреждение в лог;</li>
 *   <li>при сбое падения — {@link DatabaseException} (mod не продолжает работать).</li>
 * </ul>
 */
public final class MigrationRunner {

    public static final String RESOURCE_DIR = "vauction/migrations";

    /** Все миграции по порядку (по номеру версии, извлекаемому из имени файла). */
    private static final String[] MIGRATION_FILES = {
            "V001__initial_schema.sql",
            "V002__buy_orders.sql",
            "V003__unified_market.sql",
            "V004__order_processing_state.sql",
            "V005__bounded_work.sql",
            "V006__gui_read_indexes.sql",
            "V007__player_experience.sql",
            "V008__market_categories.sql",
            "V009__simple_listings.sql"
    };

    private static final Logger LOGGER = LogManager.getLogger("VAuction");

    public record Result(int appliedCount, int schemaVersion, List<String> appliedFiles) {
    }

    private MigrationRunner() {}

    /** Применить недостающие миграции к БД. */
    public static Result run(Connection connection) {
        try {
            createMetaTable(connection);
            int current = currentVersion(connection);
            List<Migration> migrations = loadMigrations();
            migrations.sort(Comparator.comparingInt(m -> m.version));

            int lastVersion = current;
            int applied = 0;
            List<String> appliedFiles = new ArrayList<>();
            for (Migration m : migrations) {
                if (m.version <= current) {
                    verifyChecksum(connection, m);
                    continue;
                }
                applyOne(connection, m);
                applied++;
                appliedFiles.add(m.fileName);
                lastVersion = m.version;
            }
            return new Result(applied, lastVersion, appliedFiles);
        } catch (SQLException e) {
            throw new DatabaseException("migration failed", e);
        }
    }

    /* -------------------------------- internals -------------------------------- */

    private record Migration(int version, String fileName, String description, String sql) {
    }

    private static void createMetaTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INTEGER PRIMARY KEY, "
                    + "description VARCHAR(255) NOT NULL, "
                    + "checksum VARCHAR(64) NOT NULL, "
                    + "applied_at BIGINT NOT NULL)");
        }
    }

    private static int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static List<Migration> loadMigrations() {
        List<Migration> out = new ArrayList<>();
        for (String file : MIGRATION_FILES) {
            String sql = readResource(RESOURCE_DIR + "/" + file);
            if (sql == null) {
                throw new DatabaseException("миграция не найдена в classpath: " + file);
            }
            int version = parseVersion(file);
            String description = file.replaceFirst("^V\\d+__", "").replace(".sql", "");
            out.add(new Migration(version, file, description, sql));
        }
        return out;
    }

    /** Извлекает номер версии из имени файла {@code V001__name.sql}. */
    static int parseVersion(String fileName) {
        int end = 1;
        while (end < fileName.length() && Character.isDigit(fileName.charAt(end))) {
            end++;
        }
        if (end == 1) {
            throw new DatabaseException("некорректное имя миграции (ожидалось V###__...sql): " + fileName);
        }
        return Integer.parseInt(fileName.substring(1, end));
    }

    private static String readResource(String path) {
        try (InputStream in = MigrationRunner.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatabaseException("не удалось прочитать миграцию: " + path, e);
        }
    }

    private static void verifyChecksum(Connection c, Migration m) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT checksum FROM schema_version WHERE version = ?")) {
            ps.setInt(1, m.version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String stored = rs.getString(1);
                    String actual = sha256(m.sql);
                    if (!stored.equals(actual)) {
                        LOGGER.warn("VAuction: содержимое применённой миграции {} изменилось "
                                        + "(stored={}, file={}) — БД не трогаем",
                                m.fileName, stored, actual);
                    }
                }
            }
        }
    }

    private static void applyOne(Connection c, Migration m) {
        long appliedAt = System.currentTimeMillis();
        boolean prevAutoCommit;
        try {
            prevAutoCommit = c.getAutoCommit();
        } catch (SQLException e) {
            throw new DatabaseException("autocommit недоступен", e);
        }
        try {
            c.setAutoCommit(false);
            for (String statement : splitStatements(m.sql)) {
                try (Statement st = c.createStatement()) {
                    st.execute(statement);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO schema_version (version, description, checksum, applied_at) VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, m.version);
                ps.setString(2, m.description);
                ps.setString(3, sha256(m.sql));
                ps.setLong(4, appliedAt);
                ps.executeUpdate();
            }
            c.commit();
            LOGGER.info("Применена миграция {} (v{})", m.fileName, m.version);
        } catch (Exception e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
            }
            throw new DatabaseException("миграция не применена: " + m.fileName + ": " + e.getMessage(), e);
        } finally {
            try {
                c.setAutoCommit(prevAutoCommit);
            } catch (SQLException ignored) {
            }
        }
    }

    /** Разбивка SQL-скрипта на отдельные statements (наши скрипты без ';' в строках). */
    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            int idx = line.indexOf("--");
            if (idx >= 0) {
                line = line.substring(0, idx);
            }
            current.append(line).append('\n');
            if (line.trim().endsWith(";")) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    out.add(statement);
                }
                current.setLength(0);
            }
        }
        return out;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

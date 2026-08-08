package com.valorcraft.vauction.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Проверка схемы после миграций: ожидаемые таблицы (и несколько ключевых колонок)
 * должны существовать, иначе — {@link DatabaseException}, а мод отключает
 * функциональность (никакого «частично рабочего» состояния).
 */
public final class SchemaVerifier {

    private static final Set<String> REQUIRED_TABLES = Set.of(
            "schema_version",
            "auction_listings",
            "auction_deliveries",
            "auction_sales",
            "auction_operation_log",
            "auction_buy_orders",
            "auction_orders",
            "auction_trades"
    );

    private SchemaVerifier() {}

    public static boolean verify(Connection connection) {
        List<String> missing = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table'")) {
            Set<String> present = new java.util.HashSet<>();
            while (rs.next()) {
                present.add(rs.getString(1));
            }
            for (String table : REQUIRED_TABLES) {
                if (!present.contains(table)) {
                    missing.add(table);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("не удалось проверить схему БД", e);
        }
        if (!missing.isEmpty()) {
            throw new DatabaseException("схема БД неполная, отсутствуют таблицы: " + missing);
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(auction_orders)")) {
            boolean processingState = false;
            while (rs.next()) {
                processingState |= "processing_state".equalsIgnoreCase(rs.getString("name"));
            }
            if (!processingState) {
                throw new DatabaseException("схема БД неполная: auction_orders.processing_state отсутствует");
            }
        } catch (SQLException e) {
            throw new DatabaseException("не удалось проверить auction_orders", e);
        }
        return true;
    }
}

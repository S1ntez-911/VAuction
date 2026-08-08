package com.valorcraft.vauction.persistence;

import java.sql.Connection;
import java.util.function.Function;

/**
 * Источник JDBC-соединений. Интерфейс отделяет persistence-слой от конкретной
 * СУБД: сейчас SQLite, позже без переписывания domain/application добавляется
 * MySQL через новую реализацию (и своё семейство SQL в миграциях).
 */
public interface JdbcSource extends AutoCloseable {

    /** Открыть источник (создать файл/подключение). */
    void open() throws DatabaseException;

    /** Выполнить короткую операцию чтения в одном соединении. */
    <T> T query(Work<T> work) throws DatabaseException;

    /**
     * Выполнить блок в ЕДИНОЙ транзакции (commit при успехе, rollback при исключении).
     * Все репозитории ходят в БД только через этот метод или {@link #query}.
     */
    <T> T inTransaction(Work<T> work) throws DatabaseException;

    /** Закрыть соединение. */
    @Override
    void close();

    @FunctionalInterface
    interface Work<T> {
        T execute(Connection connection) throws Exception;
    }
}
package com.valorcraft.vauction.persistence;

/**
 * Ошибка базы данных VAuction (непроверяемая, все преобразуются в логи).
 */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
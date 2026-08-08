package com.valorcraft.vauction.economy;

/**
 * Единственная точка денежной интеграции VAuction.
 * Контракт будет расширен reserve/settle/release (через EscrowApi VEconomy)
 * на следующих этапах. VAuction НЕ имеет собственного баланса и не трогает
 * таблицы VEconomy.
 */
public interface EconomyGateway {

    /** Готова ли экономика VEconomy к операциям (мод загружен и API доступен). */
    boolean isAvailable();

    /** Диагностическая строка состояния (для логов). */
    String status();
}
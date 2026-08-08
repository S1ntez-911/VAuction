package com.valorcraft.exchange.data;

/**
 * Тип транзакции биржи (для истории и лога).
 */
public enum ExchangeTransactionType {
    /** Покупка из лота на продажу. */
    BUY_SELL_ORDER,
    /** Исполнение заявки на покупку продавцом. */
    FULFILL_BUY_ORDER,
    /** Создание лота на продажу. */
    CREATE_SELL,
    /** Создание заявки на покупку. */
    CREATE_BUY,
    /** Отмена лота или заявки. */
    CANCEL
}
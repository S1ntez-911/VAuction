package com.valorcraft.vauction.domain.trade;

/** Состояние исполнения fill. */
public enum TradeState {
    /** Создан (в стакане), деньги/предметы ещё фиксируются. */
    PENDING,
    /** Зафиксирован: seller net выплачен, delivery создана. */
    SETTLED,
    /** Ошибка: требует компенсации/ручного решения. */
    FAILED,
    /** Ручное вмешательство. */
    MANUAL_REVIEW
}
package com.fintwin.macro.domain;

public enum MacroIndicatorType {
    /** CPI month-over-month change, in percent. */
    MONTHLY_INFLATION,
    /** CBRT one-week repo rate at month end, annual percent. */
    POLICY_RATE,
    /** Monthly average USD/TRY exchange rate. */
    USD_TRY
}

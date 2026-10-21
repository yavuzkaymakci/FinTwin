package com.fintwin.transaction.domain;

public enum Channel {
    POS,
    ECOMMERCE,
    ATM,
    /** EFT or FAST (instant) money transfer. */
    EFT_FAST,
    /** Standing order / direct debit (otomatik ödeme talimatı). */
    AUTO_PAYMENT,
    /** Posting originated by the bank itself, e.g. interest. */
    SYSTEM
}

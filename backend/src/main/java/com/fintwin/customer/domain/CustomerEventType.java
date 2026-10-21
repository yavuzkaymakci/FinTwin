package com.fintwin.customer.domain;

/** Labelled customer outcomes used as training targets. */
public enum CustomerEventType {
    /** A card minimum payment or loan installment became past due. */
    LATE_PAYMENT,
    /** 90+ days past due on any credit product. */
    DEFAULT,
    /** The customer closed their relationship with the bank. */
    CHURN
}

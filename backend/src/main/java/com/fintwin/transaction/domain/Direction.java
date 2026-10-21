package com.fintwin.transaction.domain;

public enum Direction {
    /** Money leaving the account (purchase, withdrawal, outgoing transfer). */
    DEBIT,
    /** Money entering the account (salary, incoming transfer, card payment received). */
    CREDIT
}

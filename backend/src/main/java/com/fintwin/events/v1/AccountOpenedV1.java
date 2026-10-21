package com.fintwin.events.v1;

import com.fintwin.events.CoreBankingEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An account was opened, or an existing account entered the feed with its opening position.
 *
 * @param accountType CHECKING, TIME_DEPOSIT or CREDIT_CARD
 * @param currency TRY or USD
 * @param openedAt original opening date (may precede occurredAt for migrated accounts)
 * @param openingBalance funds, or outstanding debt for credit cards, when the account enters the feed
 * @param creditLimit credit cards only
 * @param statementDay credit cards only
 * @param dueDay credit cards only
 * @param interestRate time deposits only, annual fraction
 */
public record AccountOpenedV1(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        long customerId,
        long customerSequence,
        long accountId,
        String accountType,
        String currency,
        LocalDate openedAt,
        BigDecimal openingBalance,
        BigDecimal creditLimit,
        Integer statementDay,
        Integer dueDay,
        BigDecimal interestRate) implements CoreBankingEvent {

    public static final String TYPE = "AccountOpened";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }
}

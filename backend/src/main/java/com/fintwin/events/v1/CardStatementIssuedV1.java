package com.fintwin.events.v1;

import com.fintwin.events.CoreBankingEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A monthly credit card statement was cut.
 */
public record CardStatementIssuedV1(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        long customerId,
        long customerSequence,
        long statementId,
        long accountId,
        LocalDate statementDate,
        LocalDate dueDate,
        BigDecimal statementBalance,
        BigDecimal minimumPayment) implements CoreBankingEvent {

    public static final String TYPE = "CardStatementIssued";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }
}

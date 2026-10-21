package com.fintwin.events.v1;

import com.fintwin.events.CoreBankingEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A card minimum payment or loan installment was not paid by its due date.
 *
 * @param product CARD_STATEMENT or LOAN_INSTALLMENT
 * @param referenceId statement id or installment id
 * @param accountId card account, for CARD_STATEMENT
 * @param loanId for LOAN_INSTALLMENT
 */
public record PaymentPastDueV1(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        long customerId,
        long customerSequence,
        String product,
        long referenceId,
        Long accountId,
        Long loanId,
        LocalDate dueDate,
        BigDecimal amountDue) implements CoreBankingEvent {

    public static final String TYPE = "PaymentPastDue";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }
}

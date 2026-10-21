package com.fintwin.events.v1;

import com.fintwin.events.CoreBankingEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A loan installment was paid.
 *
 * @param remainingPrincipal outstanding principal after this installment
 * @param daysPastDue 0 when paid on time
 */
public record LoanInstallmentSettledV1(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        long customerId,
        long customerSequence,
        long loanId,
        int installmentNo,
        LocalDate dueDate,
        LocalDate paidAt,
        BigDecimal principalAmount,
        BigDecimal interestAmount,
        BigDecimal totalAmount,
        BigDecimal remainingPrincipal,
        int daysPastDue) implements CoreBankingEvent {

    public static final String TYPE = "LoanInstallmentSettled";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }
}

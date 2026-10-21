package com.fintwin.events.v1;

import com.fintwin.events.CoreBankingEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A loan was disbursed, or an existing loan entered the feed with its position.
 *
 * @param loanType PERSONAL or MORTGAGE
 * @param installmentAmount level monthly installment
 * @param paidInstallments installments already settled when the loan enters the feed
 * @param outstandingPrincipal principal outstanding when the loan enters the feed
 */
public record LoanDisbursedV1(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        long customerId,
        long customerSequence,
        long loanId,
        String loanType,
        BigDecimal principal,
        BigDecimal monthlyInterestRate,
        int termMonths,
        LocalDate disbursedAt,
        BigDecimal installmentAmount,
        int paidInstallments,
        BigDecimal outstandingPrincipal) implements CoreBankingEvent {

    public static final String TYPE = "LoanDisbursed";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }
}

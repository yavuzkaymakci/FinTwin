package com.fintwin.twin.projection;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.events.v1.AccountClosedV1;
import com.fintwin.events.v1.AccountOpenedV1;
import com.fintwin.events.v1.CardStatementIssuedV1;
import com.fintwin.events.v1.CustomerRegisteredV1;
import com.fintwin.events.v1.LoanDisbursedV1;
import com.fintwin.events.v1.LoanInstallmentSettledV1;
import com.fintwin.events.v1.PaymentPastDueV1;
import com.fintwin.events.v1.TransactionRecordedV1;
import com.fintwin.twin.projection.TransactionClassifier.Classification;
import com.fintwin.twin.projection.TwinState.AccountPosition;
import com.fintwin.twin.projection.TwinState.LoanPosition;
import com.fintwin.twin.projection.TwinState.PendingMovement;
import com.fintwin.twin.projection.TwinState.StatementInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.TreeMap;

/**
 * Applies core banking events to a {@link TwinState}. Pure and deterministic: the state after a sequence of events
 * depends only on the events, so live projection, rebuild and as-of replay agree exactly.
 */
public final class TwinProjector {

    public static final ZoneId BANK_ZONE = ZoneId.of("Europe/Istanbul");

    private TwinProjector() {
    }

    public static TwinState newState(long customerId) {
        TwinState state = new TwinState();
        state.customerId = customerId;
        return state;
    }

    /**
     * @throws IllegalArgumentException if the event is not the next one in the customer's sequence
     */
    public static void apply(TwinState state, CoreBankingEvent event) {
        if (event.customerId() != state.customerId) {
            throw new IllegalArgumentException("Event for customer " + event.customerId() + " applied to "
                    + state.customerId);
        }
        if (event.customerSequence() != state.lastSequence + 1) {
            throw new IllegalArgumentException("Expected sequence " + (state.lastSequence + 1) + " but got "
                    + event.customerSequence() + " for customer " + state.customerId);
        }
        LocalDate day = localDate(event.occurredAt());
        switch (event) {
            case CustomerRegisteredV1 e -> {
                state.segment = e.segment();
                state.city = e.city();
                state.age = e.age();
                state.occupationGroup = e.occupationGroup();
                state.customerSince = e.customerSince();
                state.registeredOn = day;
            }
            case AccountOpenedV1 e -> {
                AccountPosition a = new AccountPosition();
                a.type = e.accountType();
                a.currency = e.currency();
                a.balanceCents = cents(e.openingBalance());
                a.creditLimitCents = cents(e.creditLimit());
                state.accounts.put(e.accountId(), a);
            }
            case AccountClosedV1 e -> {
                AccountPosition a = state.accounts.get(e.accountId());
                if (a != null) {
                    a.closed = true;
                }
            }
            case TransactionRecordedV1 e -> applyTransaction(state, e, day);
            case CardStatementIssuedV1 e -> {
                StatementInfo s = new StatementInfo();
                s.accountId = e.accountId();
                s.statementDate = e.statementDate();
                s.dueDate = e.dueDate();
                s.balanceCents = cents(e.statementBalance());
                s.minimumCents = cents(e.minimumPayment());
                state.lastStatement = s;
            }
            case LoanDisbursedV1 e -> {
                LoanPosition l = new LoanPosition();
                l.type = e.loanType();
                l.installmentCents = cents(e.installmentAmount());
                l.outstandingCents = cents(e.outstandingPrincipal());
                l.remainingInstallments = e.termMonths() - e.paidInstallments();
                l.active = l.remainingInstallments > 0;
                state.loans.put(e.loanId(), l);
                if (e.disbursedAt().equals(day)) {
                    // Disbursed now (not a migrated loan): the proceeds credit follows on the same day.
                    long amount = cents(e.principal());
                    state.pendingMovements.add(new PendingMovement("LOAN_PROCEEDS", day.toEpochDay(), amount));
                    if ("MORTGAGE".equals(e.loanType())) {
                        state.pendingMovements.add(new PendingMovement("PROPERTY_PURCHASE", day.toEpochDay(), amount));
                    }
                }
            }
            case LoanInstallmentSettledV1 e -> {
                LoanPosition l = state.loans.get(e.loanId());
                if (l != null) {
                    l.outstandingCents = cents(e.remainingPrincipal());
                    l.remainingInstallments = Math.max(0, l.remainingInstallments - 1);
                    l.active = l.remainingInstallments > 0;
                }
                state.pendingMovements.add(new PendingMovement("LOAN_INSTALLMENT", e.paidAt().toEpochDay(),
                        cents(e.totalAmount())));
            }
            case PaymentPastDueV1 e -> {
                state.latePaymentCount++;
                state.lastLatePaymentDate = day;
            }
            default -> throw new IllegalArgumentException("Unsupported event " + event.getClass().getSimpleName());
        }
        state.lastSequence = event.customerSequence();
        state.lastEventAt = event.occurredAt();
        state.currentMonth = YearMonth.from(day).toString();
        prune(state, day);
    }

    private static void applyTransaction(TwinState state, TransactionRecordedV1 t, LocalDate day) {
        long amount = cents(t.amount());
        boolean credit = "CREDIT".equals(t.direction());
        AccountPosition account = state.accounts.get(t.accountId());
        String accountType = account == null ? null : account.type;
        if (account != null) {
            boolean card = "CREDIT_CARD".equals(account.type);
            // Card balances are debt: debits increase them, credits (payments) reduce them.
            account.balanceCents += (credit != card) ? amount : -amount;
        }
        String matched = matchPending(state, day.toEpochDay(), amount, credit);
        Classification c = TransactionClassifier.classify(t, accountType, matched);
        long epochDay = day.toEpochDay();
        switch (c.flow()) {
            case SPENDING -> {
                state.dailySpend.computeIfAbsent(epochDay, d -> new TreeMap<>()).merge(c.category(), amount, Long::sum);
                state.dailyOutflow.merge(epochDay, amount, Long::sum);
            }
            case OUTFLOW -> state.dailyOutflow.merge(epochDay, amount, Long::sum);
            case INCOME -> {
                String month = YearMonth.from(day).toString();
                state.monthlyIncome.merge(month, amount, Long::sum);
                if (amount > state.largestIncome.getOrDefault(month, 0L)) {
                    state.largestIncome.put(month, amount);
                    state.incomeDay.put(month, day.getDayOfMonth());
                }
            }
            case INTERNAL, NEUTRAL -> {
                // No effect on flow features.
            }
        }
    }

    /** Consumes a pending loan movement matching this transaction (same day, amount and direction). */
    private static String matchPending(TwinState state, long epochDay, long amount, boolean credit) {
        for (Iterator<PendingMovement> it = state.pendingMovements.iterator(); it.hasNext(); ) {
            PendingMovement p = it.next();
            boolean creditKind = "LOAN_PROCEEDS".equals(p.kind());
            if (p.epochDay() == epochDay && p.amountCents() == amount && creditKind == credit) {
                it.remove();
                return p.kind();
            }
        }
        return null;
    }

    private static void prune(TwinState state, LocalDate day) {
        long oldestDay = day.toEpochDay() - TwinState.RETAINED_DAYS;
        state.dailySpend.headMap(oldestDay).clear();
        state.dailyOutflow.headMap(oldestDay).clear();
        String oldestMonth = YearMonth.from(day).minusMonths(TwinState.RETAINED_MONTHS).toString();
        state.monthlyIncome.headMap(oldestMonth).clear();
        state.incomeDay.headMap(oldestMonth).clear();
        state.largestIncome.headMap(oldestMonth).clear();
        state.pendingMovements.removeIf(p -> p.epochDay() < day.toEpochDay());
    }

    public static LocalDate localDate(Instant instant) {
        return instant.atZone(BANK_ZONE).toLocalDate();
    }

    static long cents(BigDecimal amount) {
        return amount == null ? 0 : amount.movePointRight(2).longValueExact();
    }
}

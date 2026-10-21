package com.fintwin.twin.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Projection of one customer's event stream: the minimal state from which all twin features can be computed at
 * any evaluation time. Plain mutable data (serialised to JSONB with sorted keys, so equal states have equal JSON);
 * all behaviour lives in {@link TwinProjector} and {@link TwinFeatureCalculator}. Money is in minor units.
 */
public final class TwinState {

    /** Days of daily buckets kept: exactly what the 90-day features need. */
    public static final int RETAINED_DAYS = 91;
    /** Months of income history kept; covers the 6-month features with margin. */
    public static final int RETAINED_MONTHS = 13;

    public long customerId;
    public long lastSequence;
    public Instant lastEventAt;
    /** Month (yyyy-MM, bank time zone) of the last applied event; drives monthly snapshots. */
    public String currentMonth;

    public String segment;
    public String city;
    public Integer age;
    public String occupationGroup;
    public LocalDate customerSince;
    /** Local date the customer entered the twin (first event). */
    public LocalDate registeredOn;

    public TreeMap<Long, AccountPosition> accounts = new TreeMap<>();
    public TreeMap<Long, LoanPosition> loans = new TreeMap<>();
    public StatementInfo lastStatement;

    /** Local epoch day -> spending category -> amount (consumer spending only). */
    public TreeMap<Long, TreeMap<String, Long>> dailySpend = new TreeMap<>();
    /** Local epoch day -> all external TRY outflows (spending, rent, bills, installments, interest, transfers). */
    public TreeMap<Long, Long> dailyOutflow = new TreeMap<>();
    /** yyyy-MM -> external TRY income credits. */
    public TreeMap<String, Long> monthlyIncome = new TreeMap<>();
    /** yyyy-MM -> day of month of the largest income credit. */
    public TreeMap<String, Integer> incomeDay = new TreeMap<>();
    /** yyyy-MM -> amount of the largest income credit. */
    public TreeMap<String, Long> largestIncome = new TreeMap<>();

    public int latePaymentCount;
    public LocalDate lastLatePaymentDate;

    /** Expected account movements announced by loan events, used to classify the matching transaction. */
    public List<PendingMovement> pendingMovements = new ArrayList<>();

    public static final class AccountPosition {
        public String type;
        public String currency;
        /** Funds, or outstanding debt for credit cards. */
        public long balanceCents;
        public long creditLimitCents;
        public boolean closed;
    }

    public static final class LoanPosition {
        public String type;
        public long installmentCents;
        public long outstandingCents;
        public int remainingInstallments;
        public boolean active;
    }

    public static final class StatementInfo {
        public long accountId;
        public LocalDate statementDate;
        public LocalDate dueDate;
        public long balanceCents;
        public long minimumCents;
    }

    /**
     * @param kind LOAN_PROCEEDS (credit), PROPERTY_PURCHASE (debit of mortgage proceeds) or LOAN_INSTALLMENT (debit)
     */
    public record PendingMovement(String kind, long epochDay, long amountCents) {
    }
}

package com.fintwin.events;

import com.fintwin.events.v1.AccountClosedV1;
import com.fintwin.events.v1.AccountOpenedV1;
import com.fintwin.events.v1.CardStatementIssuedV1;
import com.fintwin.events.v1.CustomerRegisteredV1;
import com.fintwin.events.v1.LoanDisbursedV1;
import com.fintwin.events.v1.LoanInstallmentSettledV1;
import com.fintwin.events.v1.PaymentPastDueV1;
import com.fintwin.events.v1.TransactionRecordedV1;

import java.util.List;
import java.util.Map;

/** Kafka topics of the core banking feed ({@code fintwin.<domain>.<event>.v<N>}) and their event types. */
public final class Topics {

    public static final String CUSTOMERS_REGISTERED = "fintwin.customers.registered.v1";
    public static final String ACCOUNTS_OPENED = "fintwin.accounts.opened.v1";
    public static final String ACCOUNTS_CLOSED = "fintwin.accounts.closed.v1";
    public static final String TRANSACTIONS_RECORDED = "fintwin.transactions.recorded.v1";
    public static final String CARD_STATEMENTS_ISSUED = "fintwin.card-statements.issued.v1";
    public static final String LOANS_DISBURSED = "fintwin.loans.disbursed.v1";
    public static final String LOAN_INSTALLMENTS_SETTLED = "fintwin.loan-installments.settled.v1";
    public static final String PAYMENTS_PAST_DUE = "fintwin.payments.past-due.v1";

    public static final String DLT_SUFFIX = ".DLT";

    /** Event record type carried by each topic (one type per topic). */
    public static final Map<String, Class<? extends CoreBankingEvent>> EVENT_TYPES = Map.of(
            CUSTOMERS_REGISTERED, CustomerRegisteredV1.class,
            ACCOUNTS_OPENED, AccountOpenedV1.class,
            ACCOUNTS_CLOSED, AccountClosedV1.class,
            TRANSACTIONS_RECORDED, TransactionRecordedV1.class,
            CARD_STATEMENTS_ISSUED, CardStatementIssuedV1.class,
            LOANS_DISBURSED, LoanDisbursedV1.class,
            LOAN_INSTALLMENTS_SETTLED, LoanInstallmentSettledV1.class,
            PAYMENTS_PAST_DUE, PaymentPastDueV1.class);

    public static final List<String> ALL = List.of(CUSTOMERS_REGISTERED, ACCOUNTS_OPENED, ACCOUNTS_CLOSED,
            TRANSACTIONS_RECORDED, CARD_STATEMENTS_ISSUED, LOANS_DISBURSED, LOAN_INSTALLMENTS_SETTLED,
            PAYMENTS_PAST_DUE);

    private Topics() {
    }

    public static String deadLetter(String topic) {
        return topic + DLT_SUFFIX;
    }

    public static String topicOf(CoreBankingEvent event) {
        return EVENT_TYPES.entrySet().stream()
                .filter(e -> e.getValue() == event.getClass())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No topic for " + event.getClass()));
    }
}

package com.fintwin.feed.outbox;

import com.fintwin.events.Topics;

/**
 * Kinds of source facts in the outbox. {@code priority} orders facts that share an instant (e.g. a customer is
 * registered before its accounts are opened, an account is closed after its last transaction).
 */
public enum OutboxKind {
    CUSTOMER_REGISTERED(0, Topics.CUSTOMERS_REGISTERED),
    ACCOUNT_OPENED(1, Topics.ACCOUNTS_OPENED),
    LOAN_DISBURSED(2, Topics.LOANS_DISBURSED),
    CARD_STATEMENT_ISSUED(3, Topics.CARD_STATEMENTS_ISSUED),
    CARD_PAYMENT_PAST_DUE(4, Topics.PAYMENTS_PAST_DUE),
    LOAN_PAYMENT_PAST_DUE(4, Topics.PAYMENTS_PAST_DUE),
    LOAN_INSTALLMENT_SETTLED(5, Topics.LOAN_INSTALLMENTS_SETTLED),
    TRANSACTION_RECORDED(6, Topics.TRANSACTIONS_RECORDED),
    ACCOUNT_CLOSED(7, Topics.ACCOUNTS_CLOSED);

    private final int priority;
    private final String topic;

    OutboxKind(int priority, String topic) {
        this.priority = priority;
        this.topic = topic;
    }

    public int priority() {
        return priority;
    }

    public String topic() {
        return topic;
    }
}

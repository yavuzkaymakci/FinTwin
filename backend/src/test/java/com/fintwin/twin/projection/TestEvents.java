package com.fintwin.twin.projection;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.events.v1.AccountOpenedV1;
import com.fintwin.events.v1.CardStatementIssuedV1;
import com.fintwin.events.v1.CustomerRegisteredV1;
import com.fintwin.events.v1.LoanDisbursedV1;
import com.fintwin.events.v1.LoanInstallmentSettledV1;
import com.fintwin.events.v1.PaymentPastDueV1;
import com.fintwin.events.v1.TransactionRecordedV1;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds a customer's event stream with consecutive sequence numbers (bank-local times). */
final class TestEvents {

    static final long CUSTOMER = 7;
    static final long CHECKING = 70;
    static final long CARD = 71;
    static final long DEPOSIT = 72;

    private final List<CoreBankingEvent> events = new ArrayList<>();
    private long sequence;
    private long ids = 1000;

    static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(TwinProjector.BANK_ZONE).toInstant();
    }

    List<CoreBankingEvent> events() {
        return events;
    }

    TestEvents registered(String when, LocalDate since) {
        return add(new CustomerRegisteredV1(UUID.randomUUID(), 1, at(when), CUSTOMER, ++sequence, since,
                "WHITE_COLLAR", 35, "ISTANBUL", "OFFICE_WORKER"));
    }

    TestEvents account(String when, long accountId, String type, String currency, String opening, String limit) {
        return add(new AccountOpenedV1(UUID.randomUUID(), 1, at(when), CUSTOMER, ++sequence, accountId, type,
                currency, LocalDate.parse(when.substring(0, 10)), new BigDecimal(opening),
                limit == null ? null : new BigDecimal(limit), limit == null ? null : 5, limit == null ? null : 15,
                null));
    }

    TestEvents tx(String when, long accountId, String direction, String amount, String channel, String text,
                  String merchantCategory, Long counterpart) {
        return add(new TransactionRecordedV1(UUID.randomUUID(), 1, at(when), CUSTOMER, ++sequence, ids++, accountId,
                new BigDecimal(amount), "TRY", direction, channel, 1, text,
                merchantCategory == null ? null : 1L, merchantCategory == null ? null : "SHOP", merchantCategory,
                counterpart));
    }

    TestEvents purchase(String when, long accountId, String amount, String category) {
        return tx(when, accountId, "DEBIT", amount, "POS", "SHOP 1234 KADIKOY IST", category, null);
    }

    TestEvents salary(String when, String amount) {
        return tx(when, CHECKING, "CREDIT", amount, "EFT_FAST", "MAAS ODEMESI ANADOLU GIDA", null, null);
    }

    TestEvents statement(String when, String balance, String minimum) {
        LocalDate date = LocalDate.parse(when.substring(0, 10));
        return add(new CardStatementIssuedV1(UUID.randomUUID(), 1, at(when), CUSTOMER, ++sequence, ids++, CARD, date,
                date.plusDays(10), new BigDecimal(balance), new BigDecimal(minimum)));
    }

    TestEvents loan(String when, long loanId, String type, String principal, LocalDate disbursedAt,
                    String installment, int term, int paid, String outstanding) {
        return add(new LoanDisbursedV1(UUID.randomUUID(), 1, at(when), CUSTOMER, ++sequence, loanId, type,
                new BigDecimal(principal), new BigDecimal("0.03"), term, disbursedAt, new BigDecimal(installment),
                paid, new BigDecimal(outstanding)));
    }

    TestEvents installment(String when, long loanId, int no, String total, String remaining) {
        LocalDate date = LocalDate.parse(when.substring(0, 10));
        return add(new LoanInstallmentSettledV1(UUID.randomUUID(), 1, at(when), CUSTOMER, ++sequence, loanId, no,
                date, date, new BigDecimal(total).subtract(BigDecimal.TEN), BigDecimal.TEN, new BigDecimal(total),
                new BigDecimal(remaining), 0));
    }

    TestEvents pastDue(String when) {
        return add(new PaymentPastDueV1(UUID.randomUUID(), 1, at(when), CUSTOMER, ++sequence, "CARD_STATEMENT", ids++,
                CARD, null, LocalDate.parse(when.substring(0, 10)).minusDays(1), new BigDecimal("100.00")));
    }

    TwinState fold() {
        TwinState state = TwinProjector.newState(CUSTOMER);
        events.forEach(e -> TwinProjector.apply(state, e));
        return state;
    }

    private TestEvents add(CoreBankingEvent e) {
        events.add(e);
        return this;
    }
}

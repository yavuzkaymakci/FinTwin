package com.fintwin.twin.service;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.events.v1.AccountOpenedV1;
import com.fintwin.events.v1.CardStatementIssuedV1;
import com.fintwin.events.v1.CustomerRegisteredV1;
import com.fintwin.events.v1.TransactionRecordedV1;
import com.fintwin.generator.simulation.CustomerSimulator;
import com.fintwin.generator.simulation.Sim;
import com.fintwin.twin.projection.TwinProjector;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A realistic single-customer event stream built directly from a simulated history (unit tests only; production
 * events come from the feed outbox).
 */
final class SyntheticStreams {

    private SyntheticStreams() {
    }

    private record Timed(Instant at, int priority, CoreBankingEvent event) {
    }

    static List<CoreBankingEvent> customerStream(CustomerSimulator simulator, int index) {
        Sim.CustomerHistory h = simulator.simulate(index);
        long id = h.customerId();
        long ids = 1;
        for (Sim.Account a : h.accounts) {
            a.id = ids++;
        }
        Instant start = h.transactions.getFirst().occurredAt.toInstant().atZone(TwinProjector.BANK_ZONE)
                .toLocalDate().withDayOfMonth(1).atStartOfDay(TwinProjector.BANK_ZONE).toInstant();
        List<Timed> timed = new ArrayList<>();
        timed.add(new Timed(max(start, h.customerSince), 0, new CustomerRegisteredV1(UUID.randomUUID(), 1, null, id, 0,
                h.customerSince, h.segment.name(), h.age, h.city, h.occupation.name())));
        for (Sim.Account a : h.accounts) {
            timed.add(new Timed(max(start, a.openedAt), 1, new AccountOpenedV1(UUID.randomUUID(), 1, null, id, 0, a.id,
                    a.type.name(), a.currency.name(), a.openedAt, Sim.toMoney(a.openingBalanceCents),
                    a.statementDay == null ? null : Sim.toMoney(a.creditLimitCents), a.statementDay, a.dueDay,
                    a.interestRate)));
        }
        for (Sim.Statement s : h.statements) {
            timed.add(new Timed(s.statementDate.atStartOfDay(TwinProjector.BANK_ZONE).toInstant(), 3,
                    new CardStatementIssuedV1(UUID.randomUUID(), 1, null, id, 0, ids++, s.card.id, s.statementDate,
                            s.dueDate, Sim.toMoney(s.balanceCents), Sim.toMoney(s.minimumPaymentCents))));
        }
        for (Sim.Transaction t : h.transactions) {
            timed.add(new Timed(t.occurredAt.toInstant(), 6, new TransactionRecordedV1(UUID.randomUUID(), 1, null, id,
                    0, ids++, t.account.id, Sim.toMoney(t.amountCents), t.account.currency.name(), t.direction.name(),
                    t.channel.name(), t.installmentCount, t.description,
                    t.merchant == null ? null : t.merchant.id(), t.merchant == null ? null : t.merchant.name(),
                    t.merchant == null ? null : t.merchant.category().code(),
                    t.counterpart == null ? null : t.counterpart.account.id)));
        }
        timed.sort(Comparator.comparing(Timed::at).thenComparingInt(Timed::priority));
        List<CoreBankingEvent> events = new ArrayList<>(timed.size());
        long seq = 1;
        for (Timed t : timed) {
            events.add(withEnvelope(t.event(), t.at(), seq++));
        }
        return events;
    }

    private static Instant max(Instant start, LocalDate date) {
        Instant d = date.atStartOfDay(TwinProjector.BANK_ZONE).toInstant();
        return d.isAfter(start) ? d : start;
    }

    private static CoreBankingEvent withEnvelope(CoreBankingEvent e, Instant at, long seq) {
        return switch (e) {
            case CustomerRegisteredV1 c -> new CustomerRegisteredV1(c.eventId(), 1, at, c.customerId(), seq,
                    c.customerSince(), c.segment(), c.age(), c.city(), c.occupationGroup());
            case AccountOpenedV1 a -> new AccountOpenedV1(a.eventId(), 1, at, a.customerId(), seq, a.accountId(),
                    a.accountType(), a.currency(), a.openedAt(), a.openingBalance(), a.creditLimit(), a.statementDay(),
                    a.dueDay(), a.interestRate());
            case CardStatementIssuedV1 s -> new CardStatementIssuedV1(s.eventId(), 1, at, s.customerId(), seq,
                    s.statementId(), s.accountId(), s.statementDate(), s.dueDate(), s.statementBalance(),
                    s.minimumPayment());
            case TransactionRecordedV1 t -> new TransactionRecordedV1(t.eventId(), 1, at, t.customerId(), seq,
                    t.transactionId(), t.accountId(), t.amount(), t.currency(), t.direction(), t.channel(),
                    t.installmentCount(), t.rawDescription(), t.merchantId(), t.merchantName(), t.merchantCategory(),
                    t.counterpartAccountId());
            default -> throw new IllegalArgumentException(e.getClass().getName());
        };
    }
}

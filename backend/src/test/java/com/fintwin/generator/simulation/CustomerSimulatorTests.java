package com.fintwin.generator.simulation;

import com.fintwin.account.domain.AccountType;
import com.fintwin.customer.domain.CustomerEventType;
import com.fintwin.customer.domain.CustomerStatus;
import com.fintwin.customer.domain.Segment;
import com.fintwin.generator.GeneratorFixtures;
import com.fintwin.generator.catalog.Category;
import com.fintwin.generator.profile.SegmentProfile;
import com.fintwin.transaction.domain.Channel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Invariants of the simulated portfolio, checked on a few hundred customers. */
class CustomerSimulatorTests {

    private static final int MONTHS = 24;
    private static List<Sim.CustomerHistory> portfolio;

    @BeforeAll
    static void simulate() {
        CustomerSimulator simulator = GeneratorFixtures.simulator(7, MONTHS);
        portfolio = IntStream.range(0, 400).parallel().mapToObj(simulator::simulate).toList();
    }

    @Test
    void baseIncomesRespectSegmentRanges() {
        for (Sim.CustomerHistory h : portfolio) {
            SegmentProfile p = SegmentProfile.of(h.segment);
            double base = h.latent.baseIncomeCents() / 100.0;
            // Profile ranges are at the reference month's price level, which is the fixture's start month.
            assertThat(base).isBetween(p.incomeMin() - 0.01, p.incomeMax() + 0.01);
            assertThat(h.age).isBetween(p.ageMin(), p.ageMax());
        }
    }

    @Test
    void transactionsAreWellFormed() {
        LocalDate horizonEnd = GeneratorFixtures.START.plusMonths(MONTHS - 1L).atEndOfMonth();
        for (Sim.CustomerHistory h : portfolio) {
            for (Sim.Transaction t : h.transactions) {
                assertThat(t.amountCents).isPositive();
                assertThat(t.description).isNotBlank().hasSizeLessThanOrEqualTo(128);
                LocalDate local = t.occurredAt.atZoneSameInstant(ZoneOffset.ofHours(3)).toLocalDate();
                assertThat(local).isBetween(GeneratorFixtures.START.atDay(1), horizonEnd);
                assertThat(local).isAfterOrEqualTo(t.account.openedAt);
                if (t.installmentCount > 1) {
                    assertThat(t.account.type).isEqualTo(AccountType.CREDIT_CARD);
                }
                if (t.channel == Channel.POS || t.channel == Channel.ECOMMERCE) {
                    assertThat(t.merchant).isNotNull();
                    assertThat(t.merchant.category()).isIn(t.category, Category.TELECOM);
                }
            }
        }
    }

    @Test
    void loanPlansAreConsistent() {
        long loans = 0;
        for (Sim.CustomerHistory h : portfolio) {
            for (Sim.Loan loan : h.loans) {
                loans++;
                BigDecimal principal = loan.installments.stream().map(i -> i.principal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(principal).isEqualByComparingTo(loan.principal);
                assertThat(loan.installments).hasSize(loan.termMonths);
                loan.installments.stream().filter(Sim.Installment::isPaid)
                        .forEach(i -> assertThat(i.paidCents).isEqualTo(i.totalCents()));
            }
        }
        assertThat(loans).isGreaterThan(50);
    }

    @Test
    void outcomesMatchEventsAndStopActivity() {
        for (Sim.CustomerHistory h : portfolio) {
            List<Sim.Event> defaults = h.events.stream().filter(e -> e.type() == CustomerEventType.DEFAULT).toList();
            List<Sim.Event> churns = h.events.stream().filter(e -> e.type() == CustomerEventType.CHURN).toList();
            assertThat(defaults.size() + churns.size()).isLessThanOrEqualTo(1);
            assertThat(h.outcome.defaulted()).isEqualTo(!defaults.isEmpty());
            assertThat(h.outcome.churned()).isEqualTo(!churns.isEmpty());
            defaults.forEach(e -> assertThat(e.daysPastDue()).isGreaterThanOrEqualTo(90));
            if (h.outcome.defaulted()) {
                assertThat(h.status).isEqualTo(CustomerStatus.DEFAULTED);
                assertThat(h.events).anyMatch(e -> e.type() == CustomerEventType.LATE_PAYMENT
                        && e.date().isBefore(h.outcome.defaultDate()));
            }
            LocalDate end = h.outcome.defaulted() ? h.outcome.defaultDate() : h.outcome.churnDate();
            if (end != null) {
                assertThat(h.transactions).allSatisfy(t ->
                        assertThat(t.occurredAt.atZoneSameInstant(ZoneOffset.ofHours(3)).toLocalDate())
                                .isBeforeOrEqualTo(end));
            }
            if (h.outcome.churned()) {
                assertThat(h.accounts).allSatisfy(a -> assertThat(a.closedAt).isNotNull());
            }
        }
        assertThat(portfolio).anyMatch(h -> h.outcome.defaulted());
        assertThat(portfolio).anyMatch(h -> h.outcome.churned());
    }

    @Test
    void cardStatementsRespectMinimumPaymentRules() {
        LocalDate horizonEnd = GeneratorFixtures.START.plusMonths(MONTHS - 1L).atEndOfMonth();
        for (Sim.CustomerHistory h : portfolio) {
            LocalDate end = h.outcome.defaulted() ? h.outcome.defaultDate()
                    : h.outcome.churned() ? h.outcome.churnDate() : horizonEnd;
            for (Sim.Statement s : h.statements) {
                assertThat(s.dueDate).isAfter(s.statementDate);
                assertThat(s.minimumPaymentCents).isBetween(0L, s.balanceCents);
                // A statement not yet due when activity ended cannot be late.
                if (s.dueDate.isBefore(end) && s.paidCents < s.minimumPaymentCents) {
                    assertThat(s.daysPastDue).as("customer %d statement %s due %s paid %d min %d balance %d",
                            h.index, s.statementDate, s.dueDate, s.paidCents, s.minimumPaymentCents,
                            s.balanceCents).isPositive();
                }
            }
        }
    }

    @Test
    void stressIsRecordedEveryActiveMonthAndLinkedToDefault() {
        double defaultedMeanStress = portfolio.stream().filter(h -> h.outcome.defaulted())
                .mapToDouble(h -> h.outcome.meanStress()).average().orElseThrow();
        double performingMeanStress = portfolio.stream().filter(h -> !h.outcome.defaulted())
                .mapToDouble(h -> h.outcome.meanStress()).average().orElseThrow();
        assertThat(defaultedMeanStress).isGreaterThan(performingMeanStress * 1.5);
        for (Sim.CustomerHistory h : portfolio) {
            h.monthlyStates.forEach(s -> assertThat(s.stress()).isBetween(0.0, 1.0));
            if (h.status == CustomerStatus.ACTIVE && h.customerSince.isBefore(GeneratorFixtures.START.atDay(1))) {
                assertThat(h.monthlyStates).hasSize(MONTHS);
            }
        }
    }

    @Test
    void segmentsDifferInBehaviour() {
        double studentCardShare = share(Segment.STUDENT, h -> h.accounts.stream()
                .anyMatch(a -> a.type == AccountType.CREDIT_CARD));
        double whiteCollarCardShare = share(Segment.WHITE_COLLAR, h -> h.accounts.stream()
                .anyMatch(a -> a.type == AccountType.CREDIT_CARD));
        assertThat(whiteCollarCardShare).isGreaterThan(studentCardShare + 0.3);
        assertThat(share(Segment.RETIREE, h -> h.transactions.stream()
                .anyMatch(t -> t.description.contains("EMEK") || t.description.contains("SGK")))).isGreaterThan(0.9);
    }

    private static double share(Segment segment, Predicate<Sim.CustomerHistory> predicate) {
        List<Sim.CustomerHistory> members = portfolio.stream().filter(h -> h.segment == segment).toList();
        return members.stream().filter(predicate).count() / (double) members.size();
    }
}

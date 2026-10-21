package com.fintwin.twin.projection;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.fintwin.twin.projection.TestEvents.CARD;
import static com.fintwin.twin.projection.TestEvents.CHECKING;
import static com.fintwin.twin.projection.TestEvents.DEPOSIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwinProjectorTests {

    private static TestEvents base() {
        return new TestEvents()
                .registered("2025-01-01T00:00:00", LocalDate.of(2020, 1, 1))
                .account("2025-01-01T00:00:00", CHECKING, "CHECKING", "TRY", "1000.00", null)
                .account("2025-01-01T00:00:00", CARD, "CREDIT_CARD", "TRY", "500.00", "10000.00")
                .account("2025-01-01T00:00:00", DEPOSIT, "TIME_DEPOSIT", "TRY", "20000.00", null);
    }

    @Test
    void balancesFollowOpeningPositionAndMovements() {
        TwinState s = base()
                .salary("2025-01-05T03:00:00", "30000.00")
                .purchase("2025-01-06T12:00:00", CHECKING, "250.50", "GROCERY")
                .purchase("2025-01-06T13:00:00", CARD, "1200.00", "ELECTRONICS")
                .fold();

        assertThat(s.accounts.get(CHECKING).balanceCents).isEqualTo(100_000 + 3_000_000 - 25_050);
        // Card balance is debt: a purchase increases it.
        assertThat(s.accounts.get(CARD).balanceCents).isEqualTo(50_000 + 120_000);
        assertThat(s.accounts.get(DEPOSIT).balanceCents).isEqualTo(2_000_000);
        assertThat(s.monthlyIncome).containsEntry("2025-01", 3_000_000L);
        assertThat(s.incomeDay).containsEntry("2025-01", 5);
        assertThat(s.dailySpend.get(LocalDate.of(2025, 1, 6).toEpochDay()))
                .containsEntry("GROCERY", 25_050L).containsEntry("ELECTRONICS", 120_000L);
    }

    @Test
    void ownAccountTransfersMoveBalancesButAreNeitherIncomeNorSpending() {
        TwinState s = base()
                .tx("2025-01-10T20:00:00", CHECKING, "DEBIT", "300.00", "EFT_FAST", "KK BORC ODEME ****1234", null, CARD)
                .tx("2025-01-10T20:00:00", CARD, "CREDIT", "300.00", "EFT_FAST", "ODEME - TESEKKUR EDERIZ", null,
                        CHECKING)
                .fold();

        assertThat(s.accounts.get(CHECKING).balanceCents).isEqualTo(70_000);
        assertThat(s.accounts.get(CARD).balanceCents).isEqualTo(20_000);
        assertThat(s.monthlyIncome).isEmpty();
        assertThat(s.dailyOutflow).isEmpty();
    }

    @Test
    void loanEventsDriveDebtAndClassifyTheirMovements() {
        TwinState s = base()
                .loan("2025-01-15T00:00:00", 9, "PERSONAL", "12000.00", LocalDate.of(2025, 1, 15), "1100.00", 12, 0,
                        "12000.00")
                .tx("2025-01-15T10:00:00", CHECKING, "CREDIT", "12000.00", "EFT_FAST", "KREDI KULLANDIRIM 123", null,
                        null)
                .installment("2025-02-15T00:00:00", 9, 1, "1100.00", "11210.00")
                .tx("2025-02-15T03:00:00", CHECKING, "DEBIT", "1100.00", "AUTO_PAYMENT", "KREDI TAKSIT 123", null, null)
                .fold();

        TwinState.LoanPosition loan = s.loans.get(9L);
        assertThat(loan.outstandingCents).isEqualTo(1_121_000);
        assertThat(loan.remainingInstallments).isEqualTo(11);
        assertThat(loan.active).isTrue();
        // Loan proceeds are not income; the installment is an outflow but not consumer spending.
        assertThat(s.monthlyIncome).isEmpty();
        assertThat(s.dailyOutflow).containsEntry(LocalDate.of(2025, 2, 15).toEpochDay(), 110_000L);
        assertThat(s.dailySpend).isEmpty();
        assertThat(s.pendingMovements).isEmpty();
    }

    @Test
    void migratedLoanStartsFromItsReportedPosition() {
        TwinState s = base()
                .loan("2025-01-01T00:00:00", 9, "MORTGAGE", "500000.00", LocalDate.of(2022, 3, 10), "15000.00", 120,
                        30, "420000.00")
                .fold();
        assertThat(s.loans.get(9L).remainingInstallments).isEqualTo(90);
        assertThat(s.loans.get(9L).outstandingCents).isEqualTo(42_000_000);
        assertThat(s.pendingMovements).isEmpty();
    }

    @Test
    void pastDueEventsCountLatePayments() {
        TwinState s = base().pastDue("2025-03-16T00:00:00").pastDue("2025-05-16T00:00:00").fold();
        assertThat(s.latePaymentCount).isEqualTo(2);
        assertThat(s.lastLatePaymentDate).isEqualTo(LocalDate.of(2025, 5, 16));
    }

    @Test
    void enforcesTheCustomerSequence() {
        TestEvents events = base().salary("2025-01-05T03:00:00", "100.00");
        TwinState s = TwinProjector.newState(TestEvents.CUSTOMER);
        TwinProjector.apply(s, events.events().get(0));
        assertThatThrownBy(() -> TwinProjector.apply(s, events.events().get(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Expected sequence 2");
    }

    @Test
    void prunesHistoryOutsideTheFeatureWindows() {
        TwinState s = base()
                .purchase("2025-01-06T12:00:00", CHECKING, "10.00", "GROCERY")
                .purchase("2025-06-30T12:00:00", CHECKING, "10.00", "GROCERY")
                .fold();
        assertThat(s.dailySpend).containsOnlyKeys(LocalDate.of(2025, 6, 30).toEpochDay());
    }

    @Test
    void stateSurvivesJsonRoundTrip() {
        TwinState s = base().salary("2025-01-05T03:00:00", "30000.00").statement("2025-01-05T00:00:00", "500.00",
                "100.00").fold();
        String json = TwinJson.write(s);
        assertThat(TwinJson.write(TwinJson.readState(json))).isEqualTo(json);
    }
}

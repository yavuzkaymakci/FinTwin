package com.fintwin.twin.projection;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.fintwin.twin.projection.TestEvents.CARD;
import static com.fintwin.twin.projection.TestEvents.CHECKING;
import static com.fintwin.twin.projection.TestEvents.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TwinFeatureCalculatorTests {

    private static TestEvents customer() {
        return new TestEvents()
                .registered("2025-01-01T00:00:00", LocalDate.of(2019, 1, 1))
                .account("2025-01-01T00:00:00", CHECKING, "CHECKING", "TRY", "0.00", null)
                .account("2025-01-01T00:00:00", CARD, "CREDIT_CARD", "TRY", "0.00", "20000.00");
    }

    @Test
    void spendingWindowsAndTrend() {
        TestEvents e = customer();
        // 90 days of 100 TRY/day grocery, then a heavier last 30 days (200 TRY/day).
        LocalDate start = LocalDate.of(2025, 1, 1);
        for (int d = 0; d < 90; d++) {
            String amount = d >= 60 ? "200.00" : "100.00";
            e.purchase(start.plusDays(d) + "T12:00:00", CHECKING, amount, "GROCERY");
        }
        TwinState s = e.fold();
        TwinFeatures f = TwinFeatureCalculator.compute(s, at(start.plusDays(89) + "T23:00:00"));

        assertThat(f.spend30d()).isEqualByComparingTo("6000.00");
        assertThat(f.spend90d()).isEqualByComparingTo("12000.00");
        assertThat(f.spendByCategory30d()).containsOnlyKeys("GROCERY");
        assertThat(f.spendTrend()).isCloseTo(6000 / (12000 / 3.0) - 1, within(1e-12));
        assertThat(f.avgMonthlyExpense()).isEqualByComparingTo("4000.00");
    }

    @Test
    void incomeEstimateUsesCompleteMonthsAndRegularity() {
        TestEvents e = customer()
                .salary("2025-01-05T03:00:00", "30000.00")
                .salary("2025-02-05T03:00:00", "30000.00")
                .salary("2025-03-04T03:00:00", "36000.00")
                .salary("2025-04-05T03:00:00", "33000.00");
        TwinState s = e.fold();
        // Evaluated in April: January..March are complete, April is not.
        TwinFeatures f = TwinFeatureCalculator.compute(s, at("2025-04-20T00:00:00"));

        assertThat(f.incomeEstimate()).isEqualByComparingTo("30000.00");
        assertThat(f.incomeMonthsObserved()).isEqualTo(3);
        assertThat(f.incomeCv()).isCloseTo(TwinFeatureCalculator.coefficientOfVariation(
                List.of(3_000_000L, 3_000_000L, 3_600_000L)), within(1e-12));
        // Pay days 5, 5, 4 -> mode 5, RMS distance sqrt(1/3).
        assertThat(f.salaryDayDeviation()).isCloseTo(Math.sqrt(1.0 / 3), within(1e-12));
    }

    @Test
    void salaryDayDeviationWrapsAroundMonthEnds() {
        TwinState s = customer().fold();
        s.incomeDay.put("2025-01", 1);
        s.incomeDay.put("2025-02", 1);
        s.incomeDay.put("2025-03", 30);
        assertThat(TwinFeatureCalculator.salaryDayDeviation(s, java.time.YearMonth.of(2025, 4)))
                .isCloseTo(Math.sqrt(1.0 / 3), within(1e-12));
    }

    @Test
    void monthsWithoutIncomeCountAsZeroAfterRegistration() {
        TwinState s = customer().salary("2025-01-05T03:00:00", "30000.00").fold();
        TwinFeatures f = TwinFeatureCalculator.compute(s, at("2025-04-10T00:00:00"));
        assertThat(f.incomeMonthsObserved()).isEqualTo(3);
        assertThat(f.incomeEstimate()).isEqualByComparingTo("0.00");
        assertThat(f.debtToIncome()).isNull();
    }

    @Test
    void obligationsDebtToIncomeUtilisationAndBuffer() {
        TestEvents e = customer()
                .salary("2025-01-05T03:00:00", "20000.00")
                .salary("2025-02-05T03:00:00", "20000.00")
                .salary("2025-03-05T03:00:00", "20000.00")
                .loan("2025-03-10T00:00:00", 9, "PERSONAL", "24000.00", LocalDate.of(2024, 3, 10), "2500.00", 12, 2,
                        "20000.00")
                .purchase("2025-03-20T12:00:00", CARD, "5000.00", "ELECTRONICS")
                .statement("2025-03-25T00:00:00", "5000.00", "1000.00");
        TwinFeatures f = TwinFeatureCalculator.compute(e.fold(), at("2025-04-01T00:00:00"));

        assertThat(f.monthlyObligations()).isEqualByComparingTo("3500.00");
        assertThat(f.debtToIncome()).isCloseTo(3500 / 20000.0, within(1e-12));
        assertThat(f.utilizationRate()).isCloseTo(0.25, within(1e-12));
        assertThat(f.totalDebt()).isEqualByComparingTo("25000.00");
        assertThat(f.activeLoans()).isEqualTo(1);
        // Checking 60,000 TRY; outflows in the last 90 days: one 5,000 TRY purchase -> 1,666.66 / month.
        assertThat(f.checkingBalance()).isEqualByComparingTo("60000.00");
        assertThat(f.liquidityBufferMonths()).isCloseTo(6_000_000.0 / 166_666, within(1e-9));
        // An old statement no longer counts as an obligation.
        assertThat(TwinFeatureCalculator.compute(e.fold(), at("2025-06-30T00:00:00")).monthlyObligations())
                .isEqualByComparingTo("2500.00");
    }

    @Test
    void latePaymentRecency() {
        TwinState s = customer().pastDue("2025-03-16T00:00:00").fold();
        TwinFeatures f = TwinFeatureCalculator.compute(s, at("2025-03-26T08:00:00"));
        assertThat(f.latePaymentCount()).isEqualTo(1);
        assertThat(f.daysSinceLastLatePayment()).isEqualTo(10);
    }

    @Test
    void undefinedFeaturesAreNull() {
        TwinState s = new TestEvents().registered("2025-01-01T00:00:00", LocalDate.of(2019, 1, 1))
                .account("2025-01-01T00:00:00", CHECKING, "CHECKING", "TRY", "10.00", null).fold();
        TwinFeatures f = TwinFeatureCalculator.compute(s, at("2025-01-02T00:00:00"));
        assertThat(f.utilizationRate()).isNull();
        assertThat(f.spendTrend()).isNull();
        assertThat(f.incomeEstimate()).isNull();
        assertThat(f.liquidityBufferMonths()).isNull();
        assertThat(f.daysSinceLastLatePayment()).isNull();
    }
}

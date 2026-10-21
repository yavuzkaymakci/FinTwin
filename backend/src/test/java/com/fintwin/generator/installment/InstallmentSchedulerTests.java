package com.fintwin.generator.installment;

import com.fintwin.generator.installment.InstallmentScheduler.ScheduledInstallment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstallmentSchedulerTests {

    private static final LocalDate DISBURSED = LocalDate.of(2025, 1, 31);

    @ParameterizedTest
    @CsvSource({
            "30000.00, 0.0349, 12",
            "50000.00, 0.0425, 36",
            "99999.99, 0.0310, 24",
            "1234567.89, 0.0289, 120",
            "5000.00, 0.0000, 3",
            "7777.77, 0.0500, 7"
    })
    void principalPartsSumExactlyToThePrincipal(BigDecimal principal, BigDecimal rate, int term) {
        List<ScheduledInstallment> plan = InstallmentScheduler.schedule(principal, rate, term, DISBURSED);

        assertThat(plan).hasSize(term);
        BigDecimal principalSum = plan.stream().map(ScheduledInstallment::principal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interestSum = plan.stream().map(ScheduledInstallment::interest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSum = plan.stream().map(ScheduledInstallment::total).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(principalSum).isEqualByComparingTo(principal);
        assertThat(totalSum).isEqualByComparingTo(principal.add(interestSum));
        assertThat(plan.getLast().remainingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);
        plan.forEach(i -> {
            assertThat(i.principal().scale()).isEqualTo(2);
            assertThat(i.interest().signum()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void installmentsAreLevelAndInterestFollowsTheOutstandingBalance() {
        BigDecimal principal = new BigDecimal("30000.00");
        BigDecimal rate = new BigDecimal("0.035");
        List<ScheduledInstallment> plan = InstallmentScheduler.schedule(principal, rate, 3, DISBURSED);
        BigDecimal payment = InstallmentScheduler.monthlyPayment(principal, rate, 3);

        assertThat(payment).isEqualByComparingTo("10708.03");
        assertThat(plan.get(0).interest()).isEqualByComparingTo("1050.00");
        assertThat(plan.get(0).total()).isEqualByComparingTo(payment);
        assertThat(plan.get(1).total()).isEqualByComparingTo(payment);
        // The last installment absorbs the rounding residue (at most a few kuruş).
        assertThat(plan.get(2).total().subtract(payment).abs()).isLessThanOrEqualTo(new BigDecimal("0.05"));
        for (int i = 1; i < plan.size(); i++) {
            BigDecimal expectedInterest = plan.get(i - 1).remainingPrincipal().multiply(rate)
                    .setScale(2, RoundingMode.HALF_EVEN);
            assertThat(plan.get(i).interest()).isEqualByComparingTo(expectedInterest);
        }
    }

    @Test
    void dueDatesAreMonthlyAndClampedToMonthEnd() {
        List<ScheduledInstallment> plan = InstallmentScheduler.schedule(new BigDecimal("1000.00"),
                new BigDecimal("0.03"), 3, DISBURSED);
        assertThat(plan).extracting(ScheduledInstallment::dueDate).containsExactly(
                LocalDate.of(2025, 2, 28), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 30));
    }

    @Test
    void zeroRateSplitsPrincipalEvenly() {
        List<ScheduledInstallment> plan = InstallmentScheduler.schedule(new BigDecimal("1000.00"), BigDecimal.ZERO,
                3, DISBURSED);
        assertThat(plan).extracting(ScheduledInstallment::principal)
                .containsExactly(new BigDecimal("333.33"), new BigDecimal("333.33"), new BigDecimal("333.34"));
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> InstallmentScheduler.schedule(BigDecimal.ZERO, BigDecimal.ONE, 3, DISBURSED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstallmentScheduler.monthlyPayment(BigDecimal.TEN, BigDecimal.ONE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

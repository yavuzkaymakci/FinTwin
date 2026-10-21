package com.fintwin.generator.installment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Equal-installment (annuity) repayment plan, the standard schedule for Turkish consumer and housing loans.
 * <p>
 * Interest for each period is the outstanding principal times the monthly rate, rounded to kuruş (HALF_EVEN); the
 * last installment absorbs the rounding residue so that the principal parts sum exactly to the loan principal.
 * (KKDF/BSMV taxes on interest are intentionally not modelled.)
 */
public final class InstallmentScheduler {

    public record ScheduledInstallment(int installmentNo, LocalDate dueDate, BigDecimal principal,
                                       BigDecimal interest, BigDecimal remainingPrincipal) {

        public BigDecimal total() {
            return principal.add(interest);
        }
    }

    private static final MathContext MC = MathContext.DECIMAL64;

    private InstallmentScheduler() {
    }

    /** Level monthly payment for the given principal, monthly rate and term, rounded to kuruş. */
    public static BigDecimal monthlyPayment(BigDecimal principal, BigDecimal monthlyRate, int termMonths) {
        if (termMonths <= 0) {
            throw new IllegalArgumentException("termMonths must be positive");
        }
        if (monthlyRate.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_EVEN);
        }
        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(termMonths, MC);
        BigDecimal factor = monthlyRate.multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC);
        return principal.multiply(factor, MC).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Builds the plan; installment {@code n} falls due {@code n} months after disbursement (same day of month,
     * clamped to month end).
     */
    public static List<ScheduledInstallment> schedule(BigDecimal principal, BigDecimal monthlyRate, int termMonths,
                                                      LocalDate disbursedAt) {
        if (principal.signum() <= 0) {
            throw new IllegalArgumentException("principal must be positive");
        }
        BigDecimal payment = monthlyPayment(principal, monthlyRate, termMonths);
        List<ScheduledInstallment> plan = new ArrayList<>(termMonths);
        BigDecimal remaining = principal.setScale(2, RoundingMode.HALF_EVEN);
        for (int n = 1; n <= termMonths; n++) {
            BigDecimal interest = remaining.multiply(monthlyRate).setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal principalPart = n == termMonths ? remaining : payment.subtract(interest);
            if (principalPart.compareTo(remaining) > 0) {
                principalPart = remaining;
            }
            if (principalPart.signum() < 0) {
                principalPart = BigDecimal.ZERO.setScale(2);
            }
            remaining = remaining.subtract(principalPart);
            plan.add(new ScheduledInstallment(n, disbursedAt.plusMonths(n), principalPart, interest, remaining));
        }
        return plan;
    }
}

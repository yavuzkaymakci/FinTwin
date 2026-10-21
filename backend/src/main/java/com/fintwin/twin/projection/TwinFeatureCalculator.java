package com.fintwin.twin.projection;

import com.fintwin.twin.projection.TwinState.AccountPosition;
import com.fintwin.twin.projection.TwinState.LoanPosition;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Computes {@link TwinFeatures} from a {@link TwinState} at an evaluation instant. Windows are expressed in bank
 * local days ending with the evaluation day; "complete months" are the months before the evaluation month.
 */
public final class TwinFeatureCalculator {

    /** A statement's minimum payment counts as an obligation while the statement is this recent. */
    static final int STATEMENT_RELEVANCE_DAYS = 45;

    private TwinFeatureCalculator() {
    }

    public static TwinFeatures compute(TwinState s, Instant evaluatedAt) {
        LocalDate day = TwinProjector.localDate(evaluatedAt);
        long today = day.toEpochDay();

        long checking = 0;
        long deposit = 0;
        long usd = 0;
        long cardDebt = 0;
        long cardLimit = 0;
        boolean hasCard = false;
        for (AccountPosition a : s.accounts.values()) {
            switch (a.type) {
                case "CHECKING" -> {
                    if ("USD".equals(a.currency)) {
                        usd += a.balanceCents;
                    } else {
                        checking += a.balanceCents;
                    }
                }
                case "TIME_DEPOSIT" -> deposit += a.balanceCents;
                case "CREDIT_CARD" -> {
                    cardDebt += a.balanceCents;
                    if (!a.closed) {
                        cardLimit += a.creditLimitCents;
                        hasCard = true;
                    }
                }
                default -> {
                }
            }
        }
        long loanOutstanding = 0;
        long installments = 0;
        int activeLoans = 0;
        for (LoanPosition l : s.loans.values()) {
            if (l.active) {
                loanOutstanding += l.outstandingCents;
                installments += l.installmentCents;
                activeLoans++;
            }
        }

        SortedMap<String, Long> spend30 = spendByCategory(s, today - 29, today);
        SortedMap<String, Long> spend90 = spendByCategory(s, today - 89, today);
        long total30 = sum(spend30);
        long total90 = sum(spend90);
        long outflow90 = s.dailyOutflow.subMap(today - 89, true, today, true).values().stream()
                .mapToLong(Long::longValue).sum();
        long avgExpense = outflow90 / 3;

        List<Long> incomes = completeMonthIncomes(s, YearMonth.from(day), 6);
        List<Long> last3 = incomes.subList(Math.max(0, incomes.size() - 3), incomes.size());
        Long incomeEstimate = last3.isEmpty() ? null : median(last3);

        long statementMinimum = s.lastStatement != null
                && ChronoUnit.DAYS.between(s.lastStatement.statementDate, day) <= STATEMENT_RELEVANCE_DAYS
                ? s.lastStatement.minimumCents : 0;
        long obligations = installments + statementMinimum;

        return new TwinFeatures(
                evaluatedAt,
                money(checking),
                money(deposit),
                money(usd),
                money(cardDebt),
                money(cardLimit),
                hasCard && cardLimit > 0 ? (double) cardDebt / cardLimit : null,
                money(loanOutstanding),
                money(cardDebt + loanOutstanding),
                activeLoans,
                money(total30),
                money(total90),
                toMoneyMap(spend30),
                toMoneyMap(spend90),
                total90 > 0 ? total30 / (total90 / 3.0) - 1.0 : null,
                incomeEstimate == null ? null : money(incomeEstimate),
                incomes.size(),
                incomes.size() >= 3 ? coefficientOfVariation(incomes) : null,
                salaryDayDeviation(s, YearMonth.from(day)),
                money(obligations),
                incomeEstimate != null && incomeEstimate > 0 ? (double) obligations / incomeEstimate : null,
                s.latePaymentCount,
                s.lastLatePaymentDate == null ? null : (int) ChronoUnit.DAYS.between(s.lastLatePaymentDate, day),
                money(avgExpense),
                avgExpense > 0 ? (double) (checking + deposit) / avgExpense : null);
    }

    private static SortedMap<String, Long> spendByCategory(TwinState s, long fromDay, long toDay) {
        SortedMap<String, Long> result = new TreeMap<>();
        for (Map<String, Long> day : s.dailySpend.subMap(fromDay, true, toDay, true).values()) {
            day.forEach((category, amount) -> result.merge(category, amount, Long::sum));
        }
        return result;
    }

    /**
     * Income of the last {@code max} complete months the customer was observed in full. Months without income
     * count as zero; the registration month counts only if the customer was there from its first day.
     */
    static List<Long> completeMonthIncomes(TwinState s, YearMonth evaluationMonth, int max) {
        if (s.registeredOn == null) {
            return List.of();
        }
        YearMonth first = s.registeredOn.getDayOfMonth() == 1 ? YearMonth.from(s.registeredOn)
                : YearMonth.from(s.registeredOn).plusMonths(1);
        List<Long> incomes = new ArrayList<>();
        for (YearMonth m = evaluationMonth.minusMonths(max); m.isBefore(evaluationMonth); m = m.plusMonths(1)) {
            if (!m.isBefore(first)) {
                incomes.add(s.monthlyIncome.getOrDefault(m.toString(), 0L));
            }
        }
        return incomes;
    }

    /**
     * Root-mean-square distance (in days, wrapping around month ends) between the day of the largest monthly
     * income credit and its most frequent value, over the last 6 complete months with income.
     */
    static Double salaryDayDeviation(TwinState s, YearMonth evaluationMonth) {
        List<Integer> days = new ArrayList<>();
        for (YearMonth m = evaluationMonth.minusMonths(6); m.isBefore(evaluationMonth); m = m.plusMonths(1)) {
            Integer d = s.incomeDay.get(m.toString());
            if (d != null) {
                days.add(d);
            }
        }
        if (days.size() < 3) {
            return null;
        }
        Map<Integer, Integer> frequency = new TreeMap<>();
        days.forEach(d -> frequency.merge(d, 1, Integer::sum));
        int mode = Collections.max(frequency.entrySet(), Map.Entry.comparingByValue()).getKey();
        double sumSquares = 0;
        for (int d : days) {
            int distance = Math.abs(d - mode);
            distance = Math.min(distance, 30 - distance);
            sumSquares += (double) distance * distance;
        }
        return Math.sqrt(sumSquares / days.size());
    }

    static double coefficientOfVariation(List<Long> values) {
        double mean = values.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean <= 0) {
            return 0;
        }
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(variance) / mean;
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static long sum(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    private static SortedMap<String, BigDecimal> toMoneyMap(SortedMap<String, Long> cents) {
        SortedMap<String, BigDecimal> result = new TreeMap<>();
        cents.forEach((k, v) -> result.put(k, money(v)));
        return result;
    }

    static BigDecimal money(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}

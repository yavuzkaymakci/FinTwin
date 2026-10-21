package com.fintwin.twin.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Twin features at an evaluation instant; the inputs of the behaviour models. Definitions: docs/twin.md.
 * Money in TRY (scale 2) unless the name says otherwise; ratios are plain doubles; {@code null} = not defined
 * (e.g. no card, no observed income).
 */
public record TwinFeatures(
        Instant evaluatedAt,
        BigDecimal checkingBalance,
        BigDecimal depositBalance,
        BigDecimal usdBalance,
        BigDecimal cardDebt,
        BigDecimal cardLimit,
        Double utilizationRate,
        BigDecimal loanOutstanding,
        BigDecimal totalDebt,
        int activeLoans,
        BigDecimal spend30d,
        BigDecimal spend90d,
        SortedMap<String, BigDecimal> spendByCategory30d,
        SortedMap<String, BigDecimal> spendByCategory90d,
        Double spendTrend,
        BigDecimal incomeEstimate,
        int incomeMonthsObserved,
        Double incomeCv,
        Double salaryDayDeviation,
        BigDecimal monthlyObligations,
        Double debtToIncome,
        int latePaymentCount,
        Integer daysSinceLastLatePayment,
        BigDecimal avgMonthlyExpense,
        Double liquidityBufferMonths) {

    /** Scalar features that can be requested as a monthly history series. */
    public static final List<String> SERIES_FEATURES = List.of(
            "checkingBalance", "depositBalance", "usdBalance", "cardDebt", "cardLimit", "utilizationRate",
            "loanOutstanding", "totalDebt", "activeLoans", "spend30d", "spend90d", "spendTrend", "incomeEstimate",
            "incomeMonthsObserved", "incomeCv", "salaryDayDeviation", "monthlyObligations", "debtToIncome",
            "latePaymentCount", "daysSinceLastLatePayment", "avgMonthlyExpense", "liquidityBufferMonths");

    public static boolean isSeriesFeature(String name) {
        return SERIES_FEATURES.contains(name);
    }

    static Map<String, BigDecimal> empty() {
        return Map.of();
    }
}

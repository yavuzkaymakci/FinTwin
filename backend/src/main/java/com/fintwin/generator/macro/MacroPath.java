package com.fintwin.generator.macro;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monthly macro path the simulation runs on: price level, policy rate and USD/TRY.
 * <p>
 * The price index is 1.0 in {@code baseMonth} and compounds monthly CPI inflation. Months outside the seeded range
 * are extrapolated: forward with the last observation, backward with the average inflation of the first year.
 */
public final class MacroPath {

    /** One month of macro data; rates in percent as published. */
    public record MonthlyMacro(YearMonth month, double inflationPct, double policyRatePct, double usdTry) {
    }

    private final NavigableMap<YearMonth, MonthlyMacro> data;
    private final YearMonth baseMonth;
    private final Map<YearMonth, Double> priceIndexCache = new ConcurrentHashMap<>();
    private final double backwardInflationPct;

    public MacroPath(List<MonthlyMacro> months, YearMonth baseMonth) {
        if (months.isEmpty()) {
            throw new IllegalArgumentException("macro path needs at least one month of data");
        }
        this.data = new TreeMap<>();
        months.forEach(m -> data.put(m.month(), m));
        this.baseMonth = baseMonth;
        this.backwardInflationPct = data.values().stream().limit(12)
                .mapToDouble(MonthlyMacro::inflationPct).average().orElse(2.0);
    }

    public YearMonth baseMonth() {
        return baseMonth;
    }

    /** CPI month-over-month inflation of {@code month} as a fraction. */
    public double inflation(YearMonth month) {
        MonthlyMacro m = data.get(month);
        if (m != null) {
            return m.inflationPct() / 100.0;
        }
        return (month.isBefore(data.firstKey()) ? backwardInflationPct : data.lastEntry().getValue().inflationPct())
                / 100.0;
    }

    /** Annual policy rate as a fraction. */
    public double policyRate(YearMonth month) {
        return nearest(month).policyRatePct() / 100.0;
    }

    public double usdTry(YearMonth month) {
        return nearest(month).usdTry();
    }

    /** USD/TRY relative to the base month. */
    public double fxIndex(YearMonth month) {
        return usdTry(month) / usdTry(baseMonth);
    }

    /** Price level of {@code month} relative to the base month (base = 1.0). */
    public double priceIndex(YearMonth month) {
        return priceIndexCache.computeIfAbsent(month, this::computePriceIndex);
    }

    /** Cumulative inflation between the price levels of {@code from} and {@code to}. */
    public double cumulativeInflation(YearMonth from, YearMonth to) {
        return priceIndex(to) / priceIndex(from) - 1.0;
    }

    private double computePriceIndex(YearMonth month) {
        double index = 1.0;
        if (month.isAfter(baseMonth)) {
            for (YearMonth m = baseMonth.plusMonths(1); !m.isAfter(month); m = m.plusMonths(1)) {
                index *= 1 + inflation(m);
            }
        } else {
            for (YearMonth m = baseMonth; m.isAfter(month); m = m.minusMonths(1)) {
                index /= 1 + inflation(m);
            }
        }
        return index;
    }

    private MonthlyMacro nearest(YearMonth month) {
        MonthlyMacro m = data.get(month);
        if (m != null) {
            return m;
        }
        return month.isBefore(data.firstKey()) ? data.firstEntry().getValue() : data.lastEntry().getValue();
    }
}

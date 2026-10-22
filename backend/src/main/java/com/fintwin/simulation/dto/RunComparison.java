package com.fintwin.simulation.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Key results of several runs side by side; deltas are against the first run. */
public record RunComparison(List<Row> runs) {

    /**
     * @param defaultRate cumulative default rate over the horizon (credit customers): mean, p5, p50, p95
     * @param segmentDefaultRate mean cumulative default rate per segment
     */
    public record Row(long runId, String scenarioCode, String scenarioName, LocalDate startDate, int horizonMonths,
                      int paths, Map<String, Double> defaultRate, Map<String, Double> churnRate,
                      double expectedLoss, double lossP99, double expectedLossPctOfEad,
                      Map<String, Double> segmentDefaultRate, double defaultRateDeltaVsFirst,
                      double expectedLossDeltaVsFirst) {
    }
}

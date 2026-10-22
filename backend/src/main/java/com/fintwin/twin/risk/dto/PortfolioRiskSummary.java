package com.fintwin.twin.risk.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Portfolio view of the latest scoring run: mean PD / churn, grade distribution and segment averages. */
public record PortfolioRiskSummary(Instant scoredAt, long customers, double meanPd, double meanChurn,
                                   Map<Integer, Long> customersByGrade, List<Segment> segments, String pdModelVersion,
                                   String churnModelVersion) {

    public record Segment(String segment, long customers, double meanPd, double meanChurn) {
    }
}

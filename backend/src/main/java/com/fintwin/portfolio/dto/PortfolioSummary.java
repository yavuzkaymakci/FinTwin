package com.fintwin.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Portfolio KPIs from each active customer's latest risk score and current twin.
 *
 * @param expectedLoss      6-month expected loss: sum of PD x LGD x total debt with LGD = {@code lgd}
 * @param highRiskCustomers customers with risk grade 8 or worse
 * @param customersByGrade  risk grade 1..10 to number of customers
 */
public record PortfolioSummary(Instant scoredAt, long activeCustomers, long scoredCustomers, double meanPd,
                               double meanChurn, BigDecimal totalDebt, BigDecimal expectedLoss, double lgd,
                               long highRiskCustomers, Map<Integer, Long> customersByGrade,
                               List<SegmentSummary> segments, List<RiskyCustomer> topRiskCustomers) {

    public record RiskyCustomer(long customerId, String segment, String city, double pd, double churn, int riskGrade,
                                Double utilizationRate, BigDecimal totalDebt) {
    }
}

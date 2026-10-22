package com.fintwin.portfolio.dto;

import java.math.BigDecimal;

/** Averages of one customer segment (latest risk scores, current twin features). */
public record SegmentSummary(String segment, long customers, double meanPd, double meanChurn,
                             BigDecimal expectedLoss, BigDecimal totalDebt, Double meanUtilization,
                             Double meanLiquidityBufferMonths, long highRiskCustomers) {
}

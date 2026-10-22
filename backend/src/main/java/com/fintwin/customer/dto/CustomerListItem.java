package com.fintwin.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A row of the customer list: profile, latest risk score and key twin features (nulls when not available). */
public record CustomerListItem(long id, String segment, String city, int age, BigDecimal monthlyNetIncome,
                               String status, LocalDate customerSince, Double pd, Double churn, Integer riskGrade,
                               Instant scoredAt, Double utilizationRate, Double liquidityBufferMonths,
                               BigDecimal totalDebt, BigDecimal liquidBalance) {
}

package com.fintwin.customer.dto;

/**
 * Filters and sorting of the customer list.
 *
 * @param search   customer id or (part of) the city
 * @param sort     id, pd, churn, riskGrade, age, income, utilization, buffer or debt
 * @param minGrade lowest risk grade (1-10), inclusive
 */
public record CustomerListQuery(String search, String segment, String status, Integer minGrade, Integer maxGrade,
                                String sort, boolean descending, int page, int size) {
}

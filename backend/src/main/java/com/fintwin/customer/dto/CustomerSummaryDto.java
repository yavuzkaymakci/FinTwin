package com.fintwin.customer.dto;

import com.fintwin.customer.domain.CustomerStatus;
import com.fintwin.customer.domain.OccupationGroup;
import com.fintwin.customer.domain.Segment;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CustomerSummaryDto(
        Long id,
        int age,
        String city,
        OccupationGroup occupationGroup,
        Segment segment,
        BigDecimal monthlyNetIncome,
        LocalDate customerSince,
        CustomerStatus status) {
}

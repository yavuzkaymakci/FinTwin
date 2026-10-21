package com.fintwin.customer.dto;

import com.fintwin.customer.domain.CustomerEventType;

import java.time.LocalDate;

public record CustomerEventDto(
        Long id,
        CustomerEventType type,
        LocalDate eventDate,
        Long accountId,
        Long loanId,
        Integer daysPastDue,
        String details) {
}

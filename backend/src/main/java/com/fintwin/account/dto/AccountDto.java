package com.fintwin.account.dto;

import com.fintwin.account.domain.AccountType;
import com.fintwin.account.domain.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AccountDto(
        Long id,
        AccountType type,
        Currency currency,
        BigDecimal balance,
        LocalDate openedAt,
        LocalDate closedAt,
        BigDecimal interestRate,
        LocalDate maturityDate,
        BigDecimal creditLimit,
        Integer statementDay,
        Integer dueDay) {
}

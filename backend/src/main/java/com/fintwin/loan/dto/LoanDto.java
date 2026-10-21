package com.fintwin.loan.dto;

import com.fintwin.loan.domain.LoanStatus;
import com.fintwin.loan.domain.LoanType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanDto(
        Long id,
        LoanType type,
        BigDecimal principal,
        BigDecimal monthlyInterestRate,
        int termMonths,
        LocalDate disbursedAt,
        LoanStatus status) {
}

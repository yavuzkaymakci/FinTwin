package com.fintwin.loan.service;

import com.fintwin.loan.domain.Loan;
import com.fintwin.loan.dto.LoanDto;

public final class LoanMapper {

    private LoanMapper() {
    }

    public static LoanDto toDto(Loan loan) {
        return new LoanDto(loan.getId(), loan.getLoanType(), loan.getPrincipal(), loan.getMonthlyInterestRate(),
                loan.getTermMonths(), loan.getDisbursedAt(), loan.getStatus());
    }
}

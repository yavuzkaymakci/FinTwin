package com.fintwin.customer.dto;

import com.fintwin.account.dto.AccountDto;
import com.fintwin.loan.dto.LoanDto;

import java.util.List;

public record CustomerDetailDto(
        CustomerSummaryDto customer,
        List<AccountDto> accounts,
        List<LoanDto> loans,
        List<CustomerEventDto> events) {
}

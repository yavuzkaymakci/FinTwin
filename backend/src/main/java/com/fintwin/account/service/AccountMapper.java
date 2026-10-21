package com.fintwin.account.service;

import com.fintwin.account.domain.Account;
import com.fintwin.account.dto.AccountDto;

public final class AccountMapper {

    private AccountMapper() {
    }

    public static AccountDto toDto(Account account) {
        return new AccountDto(account.getId(), account.getAccountType(), account.getCurrency(), account.getBalance(),
                account.getOpenedAt(), account.getClosedAt(), account.getInterestRate(), account.getMaturityDate(),
                account.getCreditLimit(), account.getStatementDay(), account.getDueDay());
    }
}

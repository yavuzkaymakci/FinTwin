package com.fintwin.account.repository;

import com.fintwin.account.domain.Account;
import com.fintwin.account.domain.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByCustomerIdOrderByIdAsc(Long customerId);

    List<Account> findByCustomerIdAndAccountType(Long customerId, AccountType accountType);
}

package com.fintwin.loan.repository;

import com.fintwin.loan.domain.LoanInstallment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, Long> {

    List<LoanInstallment> findByLoanIdOrderByInstallmentNoAsc(Long loanId);

    @Query("select coalesce(sum(i.principalAmount), 0) from LoanInstallment i where i.loan.id = :loanId")
    BigDecimal sumPrincipalByLoanId(Long loanId);

    List<LoanInstallment> findByDueDateBetweenAndPaidAtIsNull(LocalDate from, LocalDate to);
}

package com.fintwin.loan.repository;

import com.fintwin.loan.domain.Loan;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findByCustomerIdOrderByDisbursedAtAscIdAsc(Long customerId);

    @EntityGraph(attributePaths = "installments")
    Optional<Loan> findWithInstallmentsById(Long id);
}

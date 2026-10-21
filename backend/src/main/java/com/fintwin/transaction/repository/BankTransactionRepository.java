package com.fintwin.transaction.repository;

import com.fintwin.transaction.domain.BankTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    /** Transactions with {@code from <= occurredAt < to}, served by idx_bank_transaction_customer_time. */
    @EntityGraph(attributePaths = {"merchant", "account"})
    Page<BankTransaction> findByCustomerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Long customerId, Instant from, Instant to, Pageable pageable);

    long countByCustomerId(Long customerId);
}

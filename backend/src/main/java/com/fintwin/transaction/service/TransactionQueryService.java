package com.fintwin.transaction.service;

import com.fintwin.common.dto.PageResponse;
import com.fintwin.transaction.domain.BankTransaction;
import com.fintwin.transaction.dto.TransactionDto;
import com.fintwin.transaction.repository.BankTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class TransactionQueryService {

    public static final int MAX_PAGE_SIZE = 500;

    private final BankTransactionRepository transactionRepository;

    public TransactionQueryService(BankTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /** Transactions of a customer in {@code [from, to)}, oldest first. */
    public PageResponse<TransactionDto> findForCustomer(Long customerId, Instant from, Instant to, int page, int size) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by("occurredAt").ascending().and(Sort.by("id")));
        return PageResponse.of(
                transactionRepository.findByCustomerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        customerId, from, to, pageRequest),
                TransactionQueryService::toDto);
    }

    private static TransactionDto toDto(BankTransaction t) {
        return new TransactionDto(t.getId(), t.getAccount().getId(), t.getAmount(), t.getCurrency(),
                t.getDirection(), t.getChannel(), t.getInstallmentCount(), t.getRawDescription(),
                t.getMerchant() == null ? null : t.getMerchant().getName(), t.getOccurredAt());
    }
}

package com.fintwin.customer.service;

import com.fintwin.account.repository.AccountRepository;
import com.fintwin.account.service.AccountMapper;
import com.fintwin.customer.domain.Customer;
import com.fintwin.customer.domain.CustomerEvent;
import com.fintwin.customer.dto.CustomerDetailDto;
import com.fintwin.customer.dto.CustomerEventDto;
import com.fintwin.customer.dto.CustomerSummaryDto;
import com.fintwin.customer.repository.CustomerEventRepository;
import com.fintwin.customer.repository.CustomerRepository;
import com.fintwin.loan.repository.LoanRepository;
import com.fintwin.loan.service.LoanMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerQueryService {

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final LoanRepository loanRepository;
    private final CustomerEventRepository customerEventRepository;

    public CustomerQueryService(CustomerRepository customerRepository, AccountRepository accountRepository,
                                LoanRepository loanRepository, CustomerEventRepository customerEventRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.loanRepository = loanRepository;
        this.customerEventRepository = customerEventRepository;
    }

    public CustomerDetailDto getCustomer(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
        return new CustomerDetailDto(
                toSummary(customer),
                accountRepository.findByCustomerIdOrderByIdAsc(customerId).stream().map(AccountMapper::toDto).toList(),
                loanRepository.findByCustomerIdOrderByDisbursedAtAscIdAsc(customerId).stream()
                        .map(LoanMapper::toDto).toList(),
                customerEventRepository.findByCustomerIdOrderByEventDateAscIdAsc(customerId).stream()
                        .map(CustomerQueryService::toEventDto).toList());
    }

    public boolean exists(Long customerId) {
        return customerRepository.existsById(customerId);
    }

    private static CustomerSummaryDto toSummary(Customer c) {
        return new CustomerSummaryDto(c.getId(), c.getAge(), c.getCity(), c.getOccupationGroup(), c.getSegment(),
                c.getMonthlyNetIncome(), c.getCustomerSince(), c.getStatus());
    }

    private static CustomerEventDto toEventDto(CustomerEvent e) {
        return new CustomerEventDto(e.getId(), e.getEventType(), e.getEventDate(), e.getAccountId(), e.getLoanId(),
                e.getDaysPastDue(), e.getDetails());
    }
}

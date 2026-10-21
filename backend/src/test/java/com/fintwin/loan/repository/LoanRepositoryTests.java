package com.fintwin.loan.repository;

import com.fintwin.RepositoryTest;
import com.fintwin.customer.domain.Customer;
import com.fintwin.customer.domain.Segment;
import com.fintwin.customer.repository.CustomerRepository;
import com.fintwin.loan.domain.Loan;
import com.fintwin.loan.domain.LoanInstallment;
import com.fintwin.loan.domain.LoanType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.fintwin.TestEntities.customer;
import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class LoanRepositoryTests {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private LoanInstallmentRepository installmentRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void cascadesInstallmentPlanAndLoadsItWithTheLoan() {
        Customer c = customerRepository.save(customer(Segment.WHITE_COLLAR));
        LocalDate disbursed = LocalDate.of(2025, 1, 15);
        Loan loan = new Loan(c, LoanType.PERSONAL, new BigDecimal("30000.00"), new BigDecimal("0.035"), 3, disbursed);
        loan.addInstallment(1, disbursed.plusMonths(1), new BigDecimal("9662.13"), new BigDecimal("1050.00"),
                new BigDecimal("20337.87"));
        loan.addInstallment(2, disbursed.plusMonths(2), new BigDecimal("10000.31"), new BigDecimal("711.83"),
                new BigDecimal("10337.56"));
        loan.addInstallment(3, disbursed.plusMonths(3), new BigDecimal("10337.56"), new BigDecimal("361.81"),
                BigDecimal.ZERO);
        loanRepository.save(loan);
        em.flush();
        em.clear();

        Loan loaded = loanRepository.findWithInstallmentsById(loan.getId()).orElseThrow();
        assertThat(loaded.getInstallments()).extracting(LoanInstallment::getInstallmentNo).containsExactly(1, 2, 3);
        assertThat(loaded.getInstallments().getFirst().getTotalAmount()).isEqualByComparingTo("10712.13");
        assertThat(installmentRepository.sumPrincipalByLoanId(loan.getId())).isEqualByComparingTo("30000.00");
        assertThat(loanRepository.findByCustomerIdOrderByDisbursedAtAscIdAsc(c.getId())).hasSize(1);
    }

    @Test
    void findsUnpaidInstallmentsDueInRange() {
        Customer c = customerRepository.save(customer(Segment.MIN_WAGE));
        LocalDate disbursed = LocalDate.of(2025, 1, 1);
        Loan loan = new Loan(c, LoanType.PERSONAL, new BigDecimal("2000.00"), BigDecimal.ZERO, 2, disbursed);
        LoanInstallment paid = loan.addInstallment(1, LocalDate.of(2025, 2, 1), new BigDecimal("1000.00"),
                BigDecimal.ZERO, new BigDecimal("1000.00"));
        paid.recordPayment(new BigDecimal("1000.00"), LocalDate.of(2025, 2, 1), 0);
        loan.addInstallment(2, LocalDate.of(2025, 3, 1), new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        loanRepository.save(loan);
        em.flush();

        assertThat(installmentRepository.findByDueDateBetweenAndPaidAtIsNull(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)))
                .extracting(LoanInstallment::getInstallmentNo)
                .containsExactly(2);
    }
}

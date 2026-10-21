package com.fintwin.loan.domain;

import com.fintwin.customer.domain.Customer;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "loan")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "loan_type", nullable = false, length = 16)
    private LoanType loanType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    /** Monthly contractual rate as a fraction, e.g. 0.0349 for 3.49% per month. */
    @Column(name = "monthly_interest_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal monthlyInterestRate;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "disbursed_at", nullable = false)
    private LocalDate disbursedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LoanStatus status;

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("installmentNo")
    private List<LoanInstallment> installments = new ArrayList<>();

    protected Loan() {
    }

    public Loan(Customer customer, LoanType loanType, BigDecimal principal, BigDecimal monthlyInterestRate,
                int termMonths, LocalDate disbursedAt) {
        this.customer = Objects.requireNonNull(customer);
        this.loanType = Objects.requireNonNull(loanType);
        this.principal = Objects.requireNonNull(principal);
        this.monthlyInterestRate = Objects.requireNonNull(monthlyInterestRate);
        this.termMonths = termMonths;
        this.disbursedAt = Objects.requireNonNull(disbursedAt);
        this.status = LoanStatus.ACTIVE;
    }

    public LoanInstallment addInstallment(int installmentNo, LocalDate dueDate, BigDecimal principalAmount,
                                          BigDecimal interestAmount, BigDecimal remainingPrincipal) {
        LoanInstallment installment =
                new LoanInstallment(this, installmentNo, dueDate, principalAmount, interestAmount, remainingPrincipal);
        installments.add(installment);
        return installment;
    }

    public void changeStatus(LoanStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public LoanType getLoanType() {
        return loanType;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public BigDecimal getMonthlyInterestRate() {
        return monthlyInterestRate;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public LocalDate getDisbursedAt() {
        return disbursedAt;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public List<LoanInstallment> getInstallments() {
        return Collections.unmodifiableList(installments);
    }
}

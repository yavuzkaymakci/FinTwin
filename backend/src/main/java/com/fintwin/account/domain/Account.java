package com.fintwin.account.domain;

import com.fintwin.customer.domain.Customer;
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
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A customer account. Type-specific attributes (time deposit rate/maturity, card limit/cycle days) are nullable
 * columns guarded by CHECK constraints; use the static factories to create valid instances.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    /** Funds for deposit accounts; outstanding debt (positive) for credit cards. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "opened_at", nullable = false)
    private LocalDate openedAt;

    @Column(name = "closed_at")
    private LocalDate closedAt;

    @Column(name = "interest_rate", precision = 9, scale = 6)
    private BigDecimal interestRate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "statement_day")
    private Integer statementDay;

    @Column(name = "due_day")
    private Integer dueDay;

    protected Account() {
    }

    private Account(Customer customer, AccountType accountType, Currency currency, LocalDate openedAt) {
        this.customer = Objects.requireNonNull(customer);
        this.accountType = accountType;
        this.currency = Objects.requireNonNull(currency);
        this.openedAt = Objects.requireNonNull(openedAt);
        this.balance = BigDecimal.ZERO;
    }

    public static Account checking(Customer customer, Currency currency, LocalDate openedAt) {
        return new Account(customer, AccountType.CHECKING, currency, openedAt);
    }

    /**
     * @param annualInterestRate annual gross rate as a fraction, e.g. 0.42 for 42%
     */
    public static Account timeDeposit(Customer customer, LocalDate openedAt, BigDecimal annualInterestRate,
                                      LocalDate maturityDate) {
        Account account = new Account(customer, AccountType.TIME_DEPOSIT, Currency.TRY, openedAt);
        account.interestRate = Objects.requireNonNull(annualInterestRate);
        account.maturityDate = Objects.requireNonNull(maturityDate);
        return account;
    }

    public static Account creditCard(Customer customer, LocalDate openedAt, BigDecimal creditLimit,
                                     int statementDay, int dueDay) {
        Account account = new Account(customer, AccountType.CREDIT_CARD, Currency.TRY, openedAt);
        account.creditLimit = Objects.requireNonNull(creditLimit);
        account.statementDay = statementDay;
        account.dueDay = dueDay;
        return account;
    }

    public void updateBalance(BigDecimal balance) {
        this.balance = Objects.requireNonNull(balance);
    }

    public void close(LocalDate closedAt) {
        this.closedAt = Objects.requireNonNull(closedAt);
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public LocalDate getOpenedAt() {
        return openedAt;
    }

    public LocalDate getClosedAt() {
        return closedAt;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public LocalDate getMaturityDate() {
        return maturityDate;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public Integer getStatementDay() {
        return statementDay;
    }

    public Integer getDueDay() {
        return dueDay;
    }
}

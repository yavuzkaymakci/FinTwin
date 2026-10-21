package com.fintwin.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Monthly credit card statement (ekstre) with the customer's payment against it. */
@Entity
@Table(name = "card_statement")
public class CardStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "statement_date", nullable = false)
    private LocalDate statementDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "statement_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal statementBalance;

    @Column(name = "minimum_payment", nullable = false, precision = 19, scale = 2)
    private BigDecimal minimumPayment;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Column(name = "days_past_due", nullable = false)
    private int daysPastDue;

    protected CardStatement() {
    }

    public CardStatement(Account account, LocalDate statementDate, LocalDate dueDate,
                         BigDecimal statementBalance, BigDecimal minimumPayment) {
        this.account = Objects.requireNonNull(account);
        this.statementDate = Objects.requireNonNull(statementDate);
        this.dueDate = Objects.requireNonNull(dueDate);
        this.statementBalance = Objects.requireNonNull(statementBalance);
        this.minimumPayment = Objects.requireNonNull(minimumPayment);
        this.paidAmount = BigDecimal.ZERO;
    }

    public void recordPayment(BigDecimal paidAmount, LocalDate paidAt, int daysPastDue) {
        this.paidAmount = Objects.requireNonNull(paidAmount);
        this.paidAt = paidAt;
        this.daysPastDue = daysPastDue;
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public LocalDate getStatementDate() {
        return statementDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getStatementBalance() {
        return statementBalance;
    }

    public BigDecimal getMinimumPayment() {
        return minimumPayment;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public LocalDate getPaidAt() {
        return paidAt;
    }

    public int getDaysPastDue() {
        return daysPastDue;
    }
}

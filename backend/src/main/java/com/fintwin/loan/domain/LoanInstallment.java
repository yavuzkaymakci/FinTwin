package com.fintwin.loan.domain;

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

/** One row of a loan's repayment plan (taksit planı). */
@Entity
@Table(name = "loan_installment")
public class LoanInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false)
    private Loan loan;

    @Column(name = "installment_no", nullable = false)
    private int installmentNo;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal interestAmount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    /** Outstanding principal after this installment is paid. */
    @Column(name = "remaining_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingPrincipal;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Column(name = "days_past_due", nullable = false)
    private int daysPastDue;

    protected LoanInstallment() {
    }

    LoanInstallment(Loan loan, int installmentNo, LocalDate dueDate, BigDecimal principalAmount,
                    BigDecimal interestAmount, BigDecimal remainingPrincipal) {
        this.loan = Objects.requireNonNull(loan);
        this.installmentNo = installmentNo;
        this.dueDate = Objects.requireNonNull(dueDate);
        this.principalAmount = Objects.requireNonNull(principalAmount);
        this.interestAmount = Objects.requireNonNull(interestAmount);
        this.totalAmount = principalAmount.add(interestAmount);
        this.remainingPrincipal = Objects.requireNonNull(remainingPrincipal);
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

    public Loan getLoan() {
        return loan;
    }

    public int getInstallmentNo() {
        return installmentNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getPrincipalAmount() {
        return principalAmount;
    }

    public BigDecimal getInterestAmount() {
        return interestAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getRemainingPrincipal() {
        return remainingPrincipal;
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

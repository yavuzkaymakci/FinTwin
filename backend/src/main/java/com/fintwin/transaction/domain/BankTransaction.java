package com.fintwin.transaction.domain;

import com.fintwin.account.domain.Account;
import com.fintwin.account.domain.Currency;
import com.fintwin.customer.domain.Customer;
import com.fintwin.merchant.domain.Merchant;
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
import java.time.Instant;
import java.util.Objects;

/**
 * A posted account movement. {@code rawDescription} is the bank-statement text as the customer sees it; the true
 * category label lives in the ground-truth schema, not here.
 */
@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id")
    private Merchant merchant;

    /** Always positive; the sign is given by {@link #direction}. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Channel channel;

    @Column(name = "installment_count", nullable = false)
    private int installmentCount;

    @Column(name = "raw_description", nullable = false, length = 128)
    private String rawDescription;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected BankTransaction() {
    }

    public BankTransaction(Account account, Customer customer, Merchant merchant, BigDecimal amount,
                           Currency currency, Direction direction, Channel channel, int installmentCount,
                           String rawDescription, Instant occurredAt) {
        this.account = Objects.requireNonNull(account);
        this.customer = Objects.requireNonNull(customer);
        this.merchant = merchant;
        this.amount = Objects.requireNonNull(amount);
        this.currency = Objects.requireNonNull(currency);
        this.direction = Objects.requireNonNull(direction);
        this.channel = Objects.requireNonNull(channel);
        this.installmentCount = installmentCount;
        this.rawDescription = Objects.requireNonNull(rawDescription);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Direction getDirection() {
        return direction;
    }

    public Channel getChannel() {
        return channel;
    }

    public int getInstallmentCount() {
        return installmentCount;
    }

    public String getRawDescription() {
        return rawDescription;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}

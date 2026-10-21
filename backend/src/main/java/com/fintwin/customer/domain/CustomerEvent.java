package com.fintwin.customer.domain;

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

import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "customer_event")
public class CustomerEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private CustomerEventType eventType;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "loan_id")
    private Long loanId;

    @Column(name = "days_past_due")
    private Integer daysPastDue;

    @Column(length = 255)
    private String details;

    protected CustomerEvent() {
    }

    public CustomerEvent(Customer customer, CustomerEventType eventType, LocalDate eventDate,
                         Long accountId, Long loanId, Integer daysPastDue, String details) {
        this.customer = Objects.requireNonNull(customer);
        this.eventType = Objects.requireNonNull(eventType);
        this.eventDate = Objects.requireNonNull(eventDate);
        this.accountId = accountId;
        this.loanId = loanId;
        this.daysPastDue = daysPastDue;
        this.details = details;
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public CustomerEventType getEventType() {
        return eventType;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getLoanId() {
        return loanId;
    }

    public Integer getDaysPastDue() {
        return daysPastDue;
    }

    public String getDetails() {
        return details;
    }
}

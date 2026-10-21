package com.fintwin.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int age;

    @Column(nullable = false, length = 32)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "occupation_group", nullable = false, length = 32)
    private OccupationGroup occupationGroup;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Segment segment;

    /** Latest known monthly net income in TRY. */
    @Column(name = "monthly_net_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyNetIncome;

    @Column(name = "customer_since", nullable = false)
    private LocalDate customerSince;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CustomerStatus status;

    protected Customer() {
    }

    public Customer(int age, String city, OccupationGroup occupationGroup, Segment segment,
                    BigDecimal monthlyNetIncome, LocalDate customerSince) {
        this.age = age;
        this.city = Objects.requireNonNull(city);
        this.occupationGroup = Objects.requireNonNull(occupationGroup);
        this.segment = Objects.requireNonNull(segment);
        this.monthlyNetIncome = Objects.requireNonNull(monthlyNetIncome);
        this.customerSince = Objects.requireNonNull(customerSince);
        this.status = CustomerStatus.ACTIVE;
    }

    public void changeStatus(CustomerStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    public Long getId() {
        return id;
    }

    public int getAge() {
        return age;
    }

    public String getCity() {
        return city;
    }

    public OccupationGroup getOccupationGroup() {
        return occupationGroup;
    }

    public Segment getSegment() {
        return segment;
    }

    public BigDecimal getMonthlyNetIncome() {
        return monthlyNetIncome;
    }

    public LocalDate getCustomerSince() {
        return customerSince;
    }

    public CustomerStatus getStatus() {
        return status;
    }
}

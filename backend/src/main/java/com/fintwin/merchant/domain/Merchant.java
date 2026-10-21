package com.fintwin.merchant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(name = "merchant")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private MerchantCategory category;

    @Column(nullable = false, length = 32)
    private String city;

    protected Merchant() {
    }

    public Merchant(String name, MerchantCategory category, String city) {
        this.name = Objects.requireNonNull(name);
        this.category = Objects.requireNonNull(category);
        this.city = Objects.requireNonNull(city);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public MerchantCategory getCategory() {
        return category;
    }

    public String getCity() {
        return city;
    }
}

package com.fintwin.macro.domain;

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

/** Monthly macro-economic data point. Seeded for now; sourced from TCMB EVDS later. */
@Entity
@Table(name = "macro_indicator")
public class MacroIndicator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MacroIndicatorType indicator;

    /** First day of the month the value refers to. */
    @Column(nullable = false)
    private LocalDate period;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal value;

    @Column(nullable = false, length = 16)
    private String source;

    protected MacroIndicator() {
    }

    public MacroIndicator(MacroIndicatorType indicator, LocalDate period, BigDecimal value, String source) {
        if (period.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("period must be the first day of a month: " + period);
        }
        this.indicator = Objects.requireNonNull(indicator);
        this.period = period;
        this.value = Objects.requireNonNull(value);
        this.source = Objects.requireNonNull(source);
    }

    public Long getId() {
        return id;
    }

    public MacroIndicatorType getIndicator() {
        return indicator;
    }

    public LocalDate getPeriod() {
        return period;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getSource() {
        return source;
    }
}

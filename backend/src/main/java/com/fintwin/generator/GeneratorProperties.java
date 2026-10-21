package com.fintwin.generator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.YearMonth;

/**
 * Synthetic data generator settings ({@code fintwin.generator.*}, see application-generate.yml).
 *
 * @param customerCount number of customers to generate
 * @param months        length of the simulated history, ending with {@code endMonth}
 * @param seed          master random seed; the same seed and settings always produce the same data
 * @param endMonth      last simulated month; fixed (not "now") so that output does not depend on the run date
 * @param reset         truncate previously generated data before writing
 * @param chunkSize     customers simulated in parallel and written per database transaction
 * @param batchSize     JDBC batch size
 * @param parallelism   simulation threads; 0 = available processors
 * @param writeThreads  concurrent database writers (one connection each)
 * @param reportPath    markdown file whose report section is updated after generation; empty = log only
 */
@Validated
@ConfigurationProperties("fintwin.generator")
public record GeneratorProperties(
        @DefaultValue("5000") @Min(1) int customerCount,
        @DefaultValue("24") @Min(1) @Max(120) int months,
        @DefaultValue("42") long seed,
        @DefaultValue("2026-09") @NotNull YearMonth endMonth,
        @DefaultValue("true") boolean reset,
        @DefaultValue("250") @Min(1) int chunkSize,
        @DefaultValue("5000") @Min(1) int batchSize,
        @DefaultValue("0") @Min(0) int parallelism,
        @DefaultValue("4") @Min(1) @Max(16) int writeThreads,
        @DefaultValue("") String reportPath) {

    public YearMonth startMonth() {
        return endMonth.minusMonths(months - 1L);
    }

    public int effectiveParallelism() {
        return parallelism > 0 ? parallelism : Runtime.getRuntime().availableProcessors();
    }
}

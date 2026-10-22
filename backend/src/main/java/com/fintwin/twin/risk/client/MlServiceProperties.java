package com.fintwin.twin.risk.client;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * ML service connection ({@code fintwin.ml.*}).
 *
 * @param batchSize customers per batch scoring call (the ML service accepts at most 1000)
 */
@Validated
@ConfigurationProperties("fintwin.ml")
public record MlServiceProperties(
        @DefaultValue("http://localhost:8000") @NotBlank String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("500") @Min(1) @Max(1000) int batchSize) {
}

package com.fintwin.feed;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Core banking feed settings ({@code fintwin.feed.*}).
 *
 * @param pageSize              outbox rows read and published per round trip
 * @param liveTickMillis        how often live mode checks the simulated clock
 * @param defaultSecondsPerDay  live speed: real seconds per simulated day
 */
@Validated
@ConfigurationProperties("fintwin.feed")
public record FeedProperties(
        @DefaultValue("10000") @Min(1) int pageSize,
        @DefaultValue("200") @Min(10) long liveTickMillis,
        @DefaultValue("5") @Positive double defaultSecondsPerDay) {
}

package com.fintwin.assistant.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Assistant limits ({@code fintwin.assistant.*}).
 *
 * @param maxToolCalls   tool calls allowed per chat request
 * @param memoryMessages messages kept in the conversation window
 * @param maxWaitSeconds longest the scenario assistant may wait for a simulation run inside one request
 */
@Validated
@ConfigurationProperties("fintwin.assistant")
public record AssistantProperties(
        @DefaultValue("8") @Min(1) @Max(30) int maxToolCalls,
        @DefaultValue("20") @Min(2) @Max(200) int memoryMessages,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("60") @Min(0) @Max(300) int maxWaitSeconds) {

    public record RateLimit(@DefaultValue("20") @Min(1) int requests, @DefaultValue("10m") Duration window) {
    }
}

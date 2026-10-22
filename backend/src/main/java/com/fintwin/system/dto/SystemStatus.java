package com.fintwin.system.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintwin.assistant.dto.AiProviderInfo;

import java.time.Instant;
import java.util.List;

/**
 * Status shown in the UI header and the system page.
 *
 * @param ml     reachability of the ML service, the circuit breaker state and the active model versions
 * @param replay mode of the core banking feed (IDLE, BULK, LIVE, ...)
 */
public record SystemStatus(Instant time, Ml ml, AiProviderInfo ai, Replay replay) {

    public record Ml(boolean reachable, String circuitBreaker, String error, List<Model> models) {
    }

    /** {@code summary}: test AUC, Gini, KS, Brier and calibration (PD / churn) or macro F1 (categorisation). */
    public record Model(String name, String version, String trainedAt, JsonNode summary) {
    }

    public record Replay(String mode, Instant simulatedTime, Double secondsPerDay) {
    }
}

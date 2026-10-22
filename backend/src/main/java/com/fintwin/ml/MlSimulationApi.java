package com.fintwin.ml;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Wire format of the ML service's simulation endpoints (see ml-service app/api/simulations.py). */
public final class MlSimulationApi {

    private MlSimulationApi() {
    }

    /** {@code scenario} / {@code baseline} are ScenarioParameters JSON; {@code assumptions} may be null (defaults). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SimulationRequest(JsonNode scenario, JsonNode baseline, LocalDate startDate, JsonNode assumptions,
                                    Integer topCustomers, Boolean includeForecasts) {
    }

    /** Job status; {@code result} is present once the job succeeded. */
    public record Job(String jobId, String status, double progress, OffsetDateTime submittedAt,
                      OffsetDateTime startedAt, OffsetDateTime finishedAt, String error, Double durationSeconds,
                      Double peakRssMb, JsonNode result) {
    }

    public record ForecastPage(String jobId, int total, int offset, List<JsonNode> items) {
    }
}

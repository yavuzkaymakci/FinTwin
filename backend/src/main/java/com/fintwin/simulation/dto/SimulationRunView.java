package com.fintwin.simulation.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

/** A simulation run; {@code result} (portfolio, segments, migration, top customers) once it succeeded. */
public record SimulationRunView(long id, long scenarioId, String scenarioCode, String scenarioName,
                                Long baselineScenarioId, LocalDate startDate, String status, double progress,
                                Instant requestedAt, Instant startedAt, Instant finishedAt, String error,
                                Double durationSeconds, Double peakRssMb, JsonNode result) {
}

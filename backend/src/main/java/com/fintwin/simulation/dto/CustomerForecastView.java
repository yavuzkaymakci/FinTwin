package com.fintwin.simulation.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;

/** Monthly P10/P50/P90 bands of balances and liquidity buffer of one customer in one run (fan chart). */
public record CustomerForecastView(long customerId, long runId, String scenarioCode, String scenarioName,
                                   LocalDate startDate, JsonNode forecast) {
}

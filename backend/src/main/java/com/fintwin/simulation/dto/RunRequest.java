package com.fintwin.simulation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

/**
 * Options of a simulation run; every field is optional.
 *
 * @param startDate          month-start twin snapshot to start from; null = current twin state
 * @param baselineScenarioId scenario to compare against with the same random numbers; null = the BASE preset
 *                           (none when running BASE itself)
 * @param assumptions        engine assumption overrides (LGD, CCF, elasticities, ...; see docs/simulation.md)
 * @param topCustomers       number of most affected customers to report (default 20)
 */
public record RunRequest(LocalDate startDate, Long baselineScenarioId, JsonNode assumptions,
                         @Min(0) @Max(200) Integer topCustomers) {

    public static RunRequest defaults() {
        return new RunRequest(null, null, null, null);
    }
}

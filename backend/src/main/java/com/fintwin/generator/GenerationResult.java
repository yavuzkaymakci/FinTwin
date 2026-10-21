package com.fintwin.generator;

import java.time.Duration;
import java.util.Map;

/**
 * Summary of a generation run.
 *
 * @param rowCounts       rows written per table
 * @param total           wall-clock time of the whole run
 * @param simulation      time spent simulating (overlaps with writing, which runs pipelined)
 * @param writing         time spent in database writes, summed over writer threads
 */
public record GenerationResult(GeneratorProperties properties, Map<String, Long> rowCounts, Duration total,
                               Duration simulation, Duration writing) {

    public long totalRows() {
        return rowCounts.values().stream().mapToLong(Long::longValue).sum();
    }
}

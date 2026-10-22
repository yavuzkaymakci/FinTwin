package com.fintwin.twin.risk.dto;

import java.time.Instant;

/**
 * @param state      IDLE, RUNNING, SUCCEEDED or FAILED (of the last run)
 * @param scoredAt   scoring timestamp shared by all rows of the run
 */
public record ScoringStatus(String state, Instant scoredAt, long twins, long scored, Instant startedAt,
                            Instant finishedAt, Double durationSeconds, String error, String circuitBreaker) {
}

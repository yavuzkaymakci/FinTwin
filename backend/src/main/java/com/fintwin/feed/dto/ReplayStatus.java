package com.fintwin.feed.dto;

import java.time.Instant;

/**
 * @param mode               IDLE, PREPARING, BULK or LIVE
 * @param simulatedTime      live: simulated clock; otherwise event time of the last published event
 * @param position           last published outbox position
 * @param totalEvents        events in the outbox
 * @param publishedTotal     events published since the outbox was prepared
 * @param sessionPublished   events published by the current/last run
 * @param eventsPerSecond    average publishing rate of the current/last run
 */
public record ReplayStatus(String mode, String datasetEpoch, Instant feedStart, Instant feedEnd,
                           Instant simulatedTime, Double secondsPerDay, long position, long totalEvents,
                           long publishedTotal, long sessionPublished, double eventsPerSecond, Instant sessionStartedAt,
                           String lastError) {
}

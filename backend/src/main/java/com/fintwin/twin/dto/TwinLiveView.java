package com.fintwin.twin.dto;

import com.fintwin.twin.store.EventStoreRepository.RecentEvent;

import java.time.Instant;
import java.util.List;

/**
 * Live view of the twin consumer.
 *
 * @param storedEvents events in the event store (counted at {@code countedAt}, cached for 30 s)
 * @param received     events received from Kafka since the backend started
 * @param processed    events applied to a projection since the backend started
 * @param duplicates   events already in the store (idempotent republishing) since the backend started
 */
public record TwinLiveView(long storedEvents, Instant countedAt, long received, long processed, long duplicates,
                           long deadLettered, List<RecentEvent> recent) {
}

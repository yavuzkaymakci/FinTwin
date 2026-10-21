package com.fintwin.feed.outbox;

import java.time.Instant;

/** Persisted replay bookkeeping ({@code feed.replay_state}). */
public record ReplayStateRow(String datasetEpoch, Instant preparedAt, Instant feedStart, Instant feedEnd,
                             long totalEvents, long cursorPosition, Instant cursorOccurredAt, long publishedTotal) {

    public boolean prepared() {
        return datasetEpoch != null;
    }
}

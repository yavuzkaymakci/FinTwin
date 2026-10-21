package com.fintwin.feed.dto;

import java.time.Instant;

public record PrepareResult(String datasetEpoch, long totalEvents, Instant feedStart, Instant feedEnd,
                            long durationMillis) {
}

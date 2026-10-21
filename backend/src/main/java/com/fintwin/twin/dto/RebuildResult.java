package com.fintwin.twin.dto;

import java.util.List;

/**
 * @param mode                VERIFY (compare only) or REPLACE (projection rewritten)
 * @param stateMismatches     customers whose rebuilt state/features differ from the stored projection
 * @param snapshotMismatches  customers whose rebuilt snapshots differ from the stored ones
 * @param sampleMismatches    up to 10 customer ids with differences
 */
public record RebuildResult(String mode, long customers, long events, long snapshots, long stateMismatches,
                            long snapshotMismatches, List<Long> sampleMismatches, long durationMillis,
                            double eventsPerSecond) {
}

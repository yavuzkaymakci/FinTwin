package com.fintwin.feed.outbox;

import java.time.Instant;

public record OutboxRow(long position, OutboxKind kind, long sourceId, long customerId, long customerSequence,
                        Instant occurredAt) {
}

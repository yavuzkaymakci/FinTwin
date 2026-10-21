package com.fintwin.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * An event published by the core banking feed. Contract shared by the producer (feed) and consumers (twin).
 * <p>
 * Every event is keyed by {@link #customerId()} and carries a {@link #customerSequence()}: a gap-free, per-customer
 * counter in event-time order across <em>all</em> topics. Kafka only orders events within one topic partition, so
 * consumers that need a customer's events in order must apply them by sequence, not by arrival.
 * <p>
 * Versioning: adding an optional field is backward compatible (consumers ignore unknown fields). Removing a field
 * or changing its meaning requires a new record version ({@code ...V2}) published on a new {@code .v2} topic.
 */
public interface CoreBankingEvent {

    /** Unique and deterministic: republishing the same source fact yields the same id. */
    UUID eventId();

    int schemaVersion();

    /** When the fact happened in the bank (event time, UTC). */
    Instant occurredAt();

    long customerId();

    /** 1-based position of this event in the customer's stream. */
    long customerSequence();

    @JsonProperty("eventType")
    String eventType();
}

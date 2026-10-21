package com.fintwin.twin.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.UUID;

public record TwinEventView(UUID eventId, long customerSequence, String eventType, Instant occurredAt, String topic,
                            Integer partition, Long offset, @JsonRawValue String payload) {
}

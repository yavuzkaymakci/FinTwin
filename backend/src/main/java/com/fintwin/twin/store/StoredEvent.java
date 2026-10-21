package com.fintwin.twin.store;

import com.fintwin.events.CoreBankingEvent;

/**
 * An event with its Kafka provenance; {@code payload} is the original JSON as received.
 */
public record StoredEvent(CoreBankingEvent event, String topic, Integer partition, Long offset, String payload) {
}

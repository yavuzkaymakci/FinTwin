package com.fintwin.events.v1;

import com.fintwin.events.CoreBankingEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An account was closed (customer attrition or write-off).
 */
public record AccountClosedV1(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        long customerId,
        long customerSequence,
        long accountId,
        LocalDate closedAt) implements CoreBankingEvent {

    public static final String TYPE = "AccountClosed";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }
}

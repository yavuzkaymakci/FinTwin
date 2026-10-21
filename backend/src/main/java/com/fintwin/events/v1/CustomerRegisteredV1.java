package com.fintwin.events.v1;

import com.fintwin.events.CoreBankingEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer became known to the bank (or entered the feed with its profile).
 *
 * @param customerSince date the relationship started
 * @param segment STUDENT, MIN_WAGE, WHITE_COLLAR, RETIREE or SELF_EMPLOYED
 */
public record CustomerRegisteredV1(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        long customerId,
        long customerSequence,
        LocalDate customerSince,
        String segment,
        int age,
        String city,
        String occupationGroup) implements CoreBankingEvent {

    public static final String TYPE = "CustomerRegistered";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }
}

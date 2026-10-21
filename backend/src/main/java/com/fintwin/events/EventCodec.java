package com.fintwin.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * JSON encoding of core banking events. Dates are ISO-8601 strings, unknown properties are ignored (forward
 * compatibility), and property order is deterministic.
 */
public final class EventCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    private EventCodec() {
    }

    public static String encode(CoreBankingEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode " + event, e);
        }
    }

    /**
     * @throws InvalidEventException if the payload is not valid JSON for the topic's event type
     */
    public static CoreBankingEvent decode(String topic, String json) {
        Class<? extends CoreBankingEvent> type = Topics.EVENT_TYPES.get(stripDeadLetterSuffix(topic));
        if (type == null) {
            throw new InvalidEventException("Unknown topic " + topic);
        }
        return decode(type, json);
    }

    public static <T extends CoreBankingEvent> T decode(Class<T> type, String json) {
        if (json == null) {
            throw new InvalidEventException("Empty payload");
        }
        T event;
        try {
            event = MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Malformed " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
        if (event.eventId() == null || event.occurredAt() == null || event.customerId() <= 0
                || event.customerSequence() <= 0) {
            throw new InvalidEventException("Missing envelope fields in " + type.getSimpleName());
        }
        return event;
    }

    /**
     * Deterministic name-based (RFC 4122 v3) id of a source fact, scoped by the data set epoch so that a regenerated
     * data set never collides with events of an earlier one.
     */
    public static UUID eventId(String datasetEpoch, String kind, long sourceId) {
        return UUID.nameUUIDFromBytes((datasetEpoch + ":" + kind + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
    }

    private static String stripDeadLetterSuffix(String topic) {
        return topic.endsWith(Topics.DLT_SUFFIX) ? topic.substring(0, topic.length() - Topics.DLT_SUFFIX.length())
                : topic;
    }
}

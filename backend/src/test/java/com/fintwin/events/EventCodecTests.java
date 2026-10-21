package com.fintwin.events;

import com.fintwin.events.v1.CardStatementIssuedV1;
import com.fintwin.events.v1.TransactionRecordedV1;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventCodecTests {

    private static final TransactionRecordedV1 TX = new TransactionRecordedV1(
            EventCodec.eventId("epoch", "TRANSACTION_RECORDED", 17), TransactionRecordedV1.SCHEMA_VERSION,
            Instant.parse("2025-03-04T09:15:00Z"), 42, 7, 17, 99, new BigDecimal("150.25"), "TRY", "DEBIT", "POS",
            1, "KARDESLER MKT 4123 KADIKOY IST", 5L, "KARDESLER MARKET", "GROCERY", null);

    @Test
    void roundTripsEveryFieldIncludingMoneyScaleAndDates() {
        String json = EventCodec.encode(TX);

        assertThat(json).contains("\"eventType\":\"TransactionRecorded\"", "\"amount\":150.25",
                "\"occurredAt\":\"2025-03-04T09:15:00Z\"", "\"schemaVersion\":1");
        assertThat(EventCodec.decode(Topics.TRANSACTIONS_RECORDED, json)).isEqualTo(TX);
    }

    @Test
    void encodingIsDeterministic() {
        assertThat(EventCodec.encode(TX)).isEqualTo(EventCodec.encode(TX));
    }

    @Test
    void ignoresUnknownFieldsForForwardCompatibility() {
        String json = EventCodec.encode(TX).replace("{", "{\"addedInALaterVersion\":true,");
        assertThat(EventCodec.decode(Topics.TRANSACTIONS_RECORDED, json)).isEqualTo(TX);
    }

    @Test
    void decodesDeadLetterTopicsWithTheOriginalType() {
        CardStatementIssuedV1 statement = new CardStatementIssuedV1(UUID.randomUUID(), 1,
                Instant.parse("2025-03-10T21:00:00Z"), 42, 8, 3, 99, LocalDate.of(2025, 3, 11),
                LocalDate.of(2025, 3, 21), new BigDecimal("1000.00"), new BigDecimal("200.00"));
        String json = EventCodec.encode(statement);
        assertThat(EventCodec.decode(Topics.deadLetter(Topics.CARD_STATEMENTS_ISSUED), json)).isEqualTo(statement);
    }

    @Test
    void rejectsMalformedOrIncompletePayloads() {
        assertThatThrownBy(() -> EventCodec.decode(Topics.TRANSACTIONS_RECORDED, "{not json"))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventCodec.decode(Topics.TRANSACTIONS_RECORDED, "{\"amount\":1}"))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("envelope");
        assertThatThrownBy(() -> EventCodec.decode("fintwin.unknown.v1", "{}"))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> EventCodec.decode(Topics.TRANSACTIONS_RECORDED, null))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void eventIdsAreDeterministicAndScopedByEpoch() {
        assertThat(EventCodec.eventId("a", "TRANSACTION_RECORDED", 1))
                .isEqualTo(EventCodec.eventId("a", "TRANSACTION_RECORDED", 1))
                .isNotEqualTo(EventCodec.eventId("b", "TRANSACTION_RECORDED", 1))
                .isNotEqualTo(EventCodec.eventId("a", "ACCOUNT_OPENED", 1))
                .isNotEqualTo(EventCodec.eventId("a", "TRANSACTION_RECORDED", 2));
        assertThat(EventCodec.eventId("a", "X", 1).version()).isEqualTo(3);
    }

    @Test
    void everyTopicHasExactlyOneEventTypeAndViceVersa() {
        assertThat(Topics.ALL).hasSameElementsAs(Topics.EVENT_TYPES.keySet());
        assertThat(Topics.EVENT_TYPES.values()).doesNotHaveDuplicates();
        assertThat(Topics.topicOf(TX)).isEqualTo(Topics.TRANSACTIONS_RECORDED);
        assertThat(Topics.ALL).allMatch(t -> t.matches("fintwin\\.[a-z-]+\\.[a-z-]+\\.v\\d+"));
    }
}

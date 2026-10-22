package com.fintwin.twin.store;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.events.EventCodec;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Append-only access to {@code twin.event_store}. */
@Repository
public class EventStoreRepository {

    private static final String COLUMNS = "event_id, customer_id, customer_seq, event_type, occurred_at, topic, "
            + "kafka_partition, kafka_offset, payload::text AS payload";

    private final JdbcTemplate jdbc;

    public EventStoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends events, skipping ids already present (idempotency).
     *
     * @return ids actually inserted
     */
    public Set<UUID> append(List<StoredEvent> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        int n = events.size();
        UUID[] ids = new UUID[n];
        Long[] customers = new Long[n];
        Long[] sequences = new Long[n];
        String[] types = new String[n];
        Integer[] versions = new Integer[n];
        Timestamp[] times = new Timestamp[n];
        String[] topics = new String[n];
        Integer[] partitions = new Integer[n];
        Long[] offsets = new Long[n];
        String[] payloads = new String[n];
        for (int i = 0; i < n; i++) {
            StoredEvent s = events.get(i);
            CoreBankingEvent e = s.event();
            ids[i] = e.eventId();
            customers[i] = e.customerId();
            sequences[i] = e.customerSequence();
            types[i] = e.eventType();
            versions[i] = e.schemaVersion();
            times[i] = Timestamp.from(e.occurredAt());
            topics[i] = s.topic();
            partitions[i] = s.partition();
            offsets[i] = s.offset();
            payloads[i] = s.payload();
        }
        // One statement per batch; ON CONFLICT covers redelivered events (same event id).
        String sql = """
                INSERT INTO twin.event_store (event_id, customer_id, customer_seq, event_type, schema_version,
                                              occurred_at, topic, kafka_partition, kafka_offset, payload)
                SELECT u.event_id, u.customer_id, u.customer_seq, u.event_type, u.schema_version, u.occurred_at,
                       u.topic, u.kafka_partition, u.kafka_offset, u.payload::jsonb
                FROM unnest(?::uuid[], ?::bigint[], ?::bigint[], ?::varchar[], ?::int[], ?::timestamptz[],
                            ?::varchar[], ?::int[], ?::bigint[], ?::text[])
                     AS u(event_id, customer_id, customer_seq, event_type, schema_version, occurred_at, topic,
                          kafka_partition, kafka_offset, payload)
                ON CONFLICT (event_id) DO NOTHING
                RETURNING event_id""";
        return jdbc.execute((ConnectionCallback<Set<UUID>>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                Array[] arrays = {con.createArrayOf("uuid", ids), con.createArrayOf("bigint", customers),
                        con.createArrayOf("bigint", sequences), con.createArrayOf("varchar", types),
                        con.createArrayOf("int4", versions), con.createArrayOf("timestamptz", times),
                        con.createArrayOf("varchar", topics), con.createArrayOf("int4", partitions),
                        con.createArrayOf("bigint", offsets), con.createArrayOf("text", payloads)};
                for (int i = 0; i < arrays.length; i++) {
                    ps.setArray(i + 1, arrays[i]);
                }
                Set<UUID> inserted = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        inserted.add(rs.getObject(1, UUID.class));
                    }
                }
                return inserted;
            }
        });
    }

    /**
     * Stored events not yet applied to each customer's projection ({@code customer_seq > last_seq}), in sequence
     * order per customer.
     */
    public Map<Long, List<CoreBankingEvent>> unapplied(Collection<Long> customerIds) {
        Map<Long, List<CoreBankingEvent>> result = new LinkedHashMap<>();
        if (customerIds.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT e.customer_id, e.topic, e.payload::text AS payload
                    FROM twin.event_store e
                    JOIN unnest(?::bigint[]) AS c(customer_id) ON c.customer_id = e.customer_id
                    LEFT JOIN twin.customer_twin_state s ON s.customer_id = e.customer_id
                    WHERE e.customer_seq > COALESCE(s.last_seq, 0)
                    ORDER BY e.customer_id, e.customer_seq""");
            ps.setArray(1, con.createArrayOf("bigint", customerIds.toArray()));
            return ps;
        }, (RowCallbackHandler) rs -> result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                .add(EventCodec.decode(rs.getString(2), rs.getString(3))));
        return result;
    }

    /** Events of a customer after {@code afterSeq} that occurred before {@code before}, in sequence order. */
    public List<CoreBankingEvent> eventsAfter(long customerId, long afterSeq, Instant before) {
        return jdbc.query("""
                SELECT topic, payload::text FROM twin.event_store
                WHERE customer_id = ? AND customer_seq > ? AND occurred_at < ? ORDER BY customer_seq""",
                (rs, i) -> EventCodec.decode(rs.getString(1), rs.getString(2)),
                customerId, afterSeq, Timestamp.from(before));
    }

    public List<EventRecord> findByCustomer(long customerId, Instant from, Instant to, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                         FROM twin.event_store
                        WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ?
                        ORDER BY customer_seq LIMIT ?""",
                (rs, i) -> toRecord(rs), customerId, Timestamp.from(from), Timestamp.from(to), limit);
    }

    /** All stored events of the given customers, in sequence order per customer. */
    public Map<Long, List<CoreBankingEvent>> eventsOf(Collection<Long> customerIds) {
        Map<Long, List<CoreBankingEvent>> result = new LinkedHashMap<>();
        if (customerIds.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT customer_id, topic, payload::text FROM twin.event_store
                    WHERE customer_id = ANY(?) ORDER BY customer_id, customer_seq""");
            ps.setArray(1, con.createArrayOf("bigint", customerIds.toArray()));
            return ps;
        }, (RowCallbackHandler) rs -> result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                .add(EventCodec.decode(rs.getString(2), rs.getString(3))));
        return result;
    }

    public List<Long> customerIds() {
        return jdbc.queryForList("SELECT DISTINCT customer_id FROM twin.event_store ORDER BY customer_id", Long.class);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM twin.event_store", Long.class);
        return n == null ? 0 : n;
    }

    public void truncate() {
        jdbc.execute("TRUNCATE TABLE twin.event_store");
    }

    /** The most recently stored events (for the live feed view). */
    public List<RecentEvent> recent(int limit) {
        return jdbc.query("""
                SELECT customer_id, customer_seq, event_type, occurred_at, recorded_at,
                       payload ->> 'amount', payload ->> 'currency', payload ->> 'direction',
                       COALESCE(payload ->> 'merchantName', payload ->> 'rawDescription')
                FROM twin.event_store ORDER BY recorded_at DESC, customer_id, customer_seq DESC LIMIT ?""",
                (rs, i) -> new RecentEvent(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9)), limit);
    }

    public record RecentEvent(long customerId, long customerSequence, String eventType, Instant occurredAt,
                              Instant recordedAt, String amount, String currency, String direction, String text) {
    }

    public record EventRecord(UUID eventId, long customerSequence, String eventType, Instant occurredAt,
                              String topic, Integer partition, Long offset, String payload) {
    }

    private static EventRecord toRecord(ResultSet rs) throws SQLException {
        return new EventRecord(rs.getObject("event_id", UUID.class), rs.getLong("customer_seq"),
                rs.getString("event_type"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("topic"),
                (Integer) rs.getObject("kafka_partition"), (Long) rs.getObject("kafka_offset"),
                rs.getString("payload"));
    }
}

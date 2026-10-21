package com.fintwin.feed.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Builds and reads the feed outbox. The outbox is derived from the core banking tables in one set-based statement
 * that assigns both the global position and the per-customer sequence.
 */
@Repository
public class OutboxRepository {

    private static final String ZONE = "'Europe/Istanbul'";

    private static final String BUILD = """
            INSERT INTO feed.outbox (position, kind, source_id, customer_id, customer_seq, occurred_at)
            SELECT row_number() OVER (ORDER BY occurred_at, priority, customer_id, source_id),
                   kind, source_id, customer_id,
                   row_number() OVER (PARTITION BY customer_id ORDER BY occurred_at, priority, source_id),
                   occurred_at
            FROM (
                SELECT 'CUSTOMER_REGISTERED' AS kind, 0 AS priority, c.id AS source_id, c.id AS customer_id,
                       GREATEST(c.customer_since, CAST(:start AS date))::timestamp AT TIME ZONE %1$s AS occurred_at
                FROM customer c
                UNION ALL
                SELECT 'ACCOUNT_OPENED', 1, a.id, a.customer_id,
                       GREATEST(a.opened_at, CAST(:start AS date))::timestamp AT TIME ZONE %1$s
                FROM account a
                UNION ALL
                SELECT 'LOAN_DISBURSED', 2, l.id, l.customer_id,
                       GREATEST(l.disbursed_at, CAST(:start AS date))::timestamp AT TIME ZONE %1$s
                FROM loan l
                UNION ALL
                SELECT 'CARD_STATEMENT_ISSUED', 3, s.id, a.customer_id, s.statement_date::timestamp AT TIME ZONE %1$s
                FROM card_statement s JOIN account a ON a.id = s.account_id
                UNION ALL
                SELECT 'CARD_PAYMENT_PAST_DUE', 4, s.id, a.customer_id,
                       (s.due_date + 1)::timestamp AT TIME ZONE %1$s
                FROM card_statement s JOIN account a ON a.id = s.account_id
                WHERE s.days_past_due > 0
                UNION ALL
                SELECT 'LOAN_PAYMENT_PAST_DUE', 4, i.id, l.customer_id,
                       (i.due_date + 1)::timestamp AT TIME ZONE %1$s
                FROM loan_installment i JOIN loan l ON l.id = i.loan_id
                WHERE i.days_past_due > 0 AND i.due_date >= CAST(:start AS date)
                UNION ALL
                SELECT 'LOAN_INSTALLMENT_SETTLED', 5, i.id, l.customer_id, i.paid_at::timestamp AT TIME ZONE %1$s
                FROM loan_installment i JOIN loan l ON l.id = i.loan_id
                WHERE i.paid_at >= CAST(:start AS date)
                UNION ALL
                SELECT 'TRANSACTION_RECORDED', 6, t.id, t.customer_id, t.occurred_at
                FROM bank_transaction t
                UNION ALL
                SELECT 'ACCOUNT_CLOSED', 7, a.id, a.customer_id,
                       (a.closed_at + 1)::timestamp AT TIME ZONE %1$s - INTERVAL '1 second'
                FROM account a WHERE a.closed_at IS NOT NULL
            ) e""".formatted(ZONE);

    private static final RowMapper<OutboxRow> ROW = (rs, i) -> new OutboxRow(rs.getLong("position"),
            OutboxKind.valueOf(rs.getString("kind")), rs.getLong("source_id"), rs.getLong("customer_id"),
            rs.getLong("customer_seq"), rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** First day of the month of the earliest transaction (local time), or null without data. */
    public LocalDate feedStartDate() {
        return jdbc.queryForObject("""
                SELECT CAST(date_trunc('month', MIN(occurred_at AT TIME ZONE %s)) AS date) FROM bank_transaction"""
                .formatted(ZONE), LocalDate.class);
    }

    /** Fingerprint of the core banking data set; changes whenever the data is regenerated. */
    public String datasetFingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(concat_ws(':', (SELECT COUNT(*) FROM customer), (SELECT COUNT(*) FROM bank_transaction),
                       (SELECT COALESCE(MAX(id), 0) FROM bank_transaction),
                       (SELECT COALESCE(SUM(amount), 0) FROM bank_transaction),
                       (SELECT COALESCE(SUM(balance), 0) FROM account)))""", String.class);
    }

    /** Rebuilds the outbox; returns the number of events. */
    public long rebuild(LocalDate feedStart) {
        jdbc.execute("TRUNCATE TABLE feed.outbox");
        return jdbc.update(BUILD.replace(":start", "?"), feedStart, feedStart, feedStart, feedStart, feedStart);
    }

    public void analyze() {
        jdbc.execute("ANALYZE feed.outbox");
    }

    /** Next rows after {@code afterPosition} with {@code occurred_at < before}, in position order. */
    public List<OutboxRow> page(long afterPosition, Instant before, int limit) {
        return jdbc.query("""
                SELECT position, kind, source_id, customer_id, customer_seq, occurred_at FROM feed.outbox
                WHERE position > ? AND occurred_at < ? ORDER BY position LIMIT ?""",
                ROW, afterPosition, Timestamp.from(before), limit);
    }

    public Instant occurredAtOfNext(long afterPosition) {
        List<Timestamp> next = jdbc.queryForList("""
                SELECT occurred_at FROM feed.outbox WHERE position > ? ORDER BY position LIMIT 1""",
                Timestamp.class, afterPosition);
        return next.isEmpty() ? null : next.getFirst().toInstant();
    }

    public Instant[] range() {
        return jdbc.queryForObject("SELECT MIN(occurred_at), MAX(occurred_at) FROM feed.outbox",
                (rs, i) -> new Instant[]{toInstant(rs.getTimestamp(1)), toInstant(rs.getTimestamp(2))});
    }

    public ReplayStateRow state() {
        return jdbc.queryForObject("""
                SELECT dataset_epoch, prepared_at, feed_start, feed_end, total_events, cursor_position,
                       cursor_occurred_at, published_total
                FROM feed.replay_state WHERE id = 1""", (rs, i) -> new ReplayStateRow(rs.getString(1),
                toInstant(rs.getTimestamp(2)), toInstant(rs.getTimestamp(3)), toInstant(rs.getTimestamp(4)),
                rs.getLong(5), rs.getLong(6), toInstant(rs.getTimestamp(7)), rs.getLong(8)));
    }

    public void savePrepared(String epoch, Instant feedStart, Instant feedEnd, long totalEvents) {
        jdbc.update("""
                UPDATE feed.replay_state SET dataset_epoch = ?, prepared_at = now(), feed_start = ?, feed_end = ?,
                       total_events = ?, cursor_position = 0, cursor_occurred_at = NULL, published_total = 0
                WHERE id = 1""", epoch, ts(feedStart), ts(feedEnd), totalEvents);
    }

    public void saveCursor(long position, Instant occurredAt, long publishedDelta) {
        jdbc.update("""
                UPDATE feed.replay_state SET cursor_position = ?, cursor_occurred_at = ?,
                       published_total = published_total + ?
                WHERE id = 1""", position, ts(occurredAt), publishedDelta);
    }

    public void resetCursor() {
        jdbc.update("UPDATE feed.replay_state SET cursor_position = 0, cursor_occurred_at = NULL WHERE id = 1");
    }

    /** Invalidates the outbox, e.g. after the core banking data was regenerated. */
    public void invalidate() {
        jdbc.execute("TRUNCATE TABLE feed.outbox");
        jdbc.update("""
                UPDATE feed.replay_state SET dataset_epoch = NULL, prepared_at = NULL, feed_start = NULL,
                       feed_end = NULL, total_events = 0, cursor_position = 0, cursor_occurred_at = NULL,
                       published_total = 0
                WHERE id = 1""");
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

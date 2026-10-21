package com.fintwin.feed.consistency;

import com.fintwin.feed.dto.ConsistencyReport;
import com.fintwin.feed.dto.ConsistencyReport.AccountDiff;
import com.fintwin.feed.dto.ConsistencyReport.CustomerComparison;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Verification tool, deliberately outside the twin package: it is the only component that reads both the core
 * banking tables and the twin projection, to prove that the twin rebuilt every balance from events alone.
 */
@Service
public class ConsistencyCheckService {

    private static final String COMPARISON = """
            SELECT a.id AS account_id, a.customer_id, a.account_type, a.balance AS core_balance,
                   ((s.state -> 'accounts' -> a.id::text ->> 'balanceCents')::numeric / 100) AS twin_balance
            FROM account a
            LEFT JOIN twin.customer_twin_state s ON s.customer_id = a.customer_id""";

    private final JdbcTemplate jdbc;

    public ConsistencyCheckService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ConsistencyReport check(int sampleSize) {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) AS accounts,
                       COUNT(*) FILTER (WHERE twin_balance = core_balance) AS matched
                FROM (%s) c""".formatted(COMPARISON));
        long accounts = ((Number) totals.get("accounts")).longValue();
        long matched = ((Number) totals.get("matched")).longValue();
        List<AccountDiff> mismatches = jdbc.query("""
                SELECT * FROM (%s) c WHERE twin_balance IS DISTINCT FROM core_balance
                ORDER BY account_id LIMIT 20""".formatted(COMPARISON), (rs, i) -> new AccountDiff(
                rs.getLong("customer_id"), rs.getLong("account_id"), rs.getString("account_type"),
                rs.getBigDecimal("core_balance"), rs.getBigDecimal("twin_balance")));
        // Deterministic sample spread over the id range.
        List<CustomerComparison> sample = jdbc.query("""
                WITH ranked AS (
                    SELECT id, ntile(?) OVER (ORDER BY id) AS bucket FROM customer
                ), chosen AS (
                    SELECT MIN(id) AS id FROM ranked GROUP BY bucket
                )
                SELECT cu.id, cu.segment, COUNT(c.account_id) AS accounts,
                       SUM(CASE WHEN c.account_type = 'CREDIT_CARD' THEN -c.core_balance ELSE c.core_balance END)
                           AS core_total,
                       SUM(CASE WHEN c.account_type = 'CREDIT_CARD' THEN -c.twin_balance ELSE c.twin_balance END)
                           AS twin_total,
                       BOOL_AND(c.twin_balance = c.core_balance) AS all_match
                FROM chosen ch JOIN customer cu ON cu.id = ch.id
                JOIN (%s) c ON c.customer_id = cu.id
                GROUP BY cu.id, cu.segment ORDER BY cu.id""".formatted(COMPARISON), (rs, i) ->
                new CustomerComparison(rs.getLong("id"), rs.getString("segment"), rs.getInt("accounts"),
                        rs.getBigDecimal("core_total"), rs.getBigDecimal("twin_total"),
                        rs.getBoolean("all_match")), Math.max(1, sampleSize));
        long outboxEvents = count("SELECT total_events FROM feed.replay_state WHERE id = 1");
        long published = count("SELECT cursor_position FROM feed.replay_state WHERE id = 1");
        long twinEvents = count("SELECT COUNT(*) FROM twin.event_store");
        return new ConsistencyReport(outboxEvents > 0 && published == outboxEvents && twinEvents == outboxEvents,
                outboxEvents, twinEvents, accounts, matched, accounts - matched, mismatches, sample);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}

package com.fintwin.twin.risk.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Twin inputs for scoring and the scores themselves ({@code twin.customer_risk_score}); twin schema only. */
@Repository
public class RiskScoreRepository {

    /** What the ML service needs to score one twin. */
    public record TwinInput(long customerId, String segment, String featuresJson, Instant featuresAsOf) {
    }

    public record StoredScore(long customerId, Instant scoredAt, Instant featuresAsOf, double pd, double churn,
                              int riskGrade, String reasonsJson, String pdModelVersion, String churnModelVersion) {
    }

    private final JdbcTemplate jdbc;

    public RiskScoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A page of twins in customer order (keyset pagination). */
    public List<TwinInput> twinsAfter(long afterCustomerId, int limit) {
        return jdbc.query("""
                SELECT customer_id, state ->> 'segment' AS segment, features::text AS features, last_event_at
                FROM twin.customer_twin_state WHERE customer_id > ? ORDER BY customer_id LIMIT ?""",
                (rs, i) -> new TwinInput(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()), afterCustomerId, limit);
    }

    public Optional<TwinInput> twin(long customerId) {
        return jdbc.query("""
                SELECT customer_id, state ->> 'segment' AS segment, features::text AS features, last_event_at
                FROM twin.customer_twin_state WHERE customer_id = ?""",
                (rs, i) -> new TwinInput(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()), customerId).stream().findFirst();
    }

    public long countTwins() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM twin.customer_twin_state", Long.class);
        return n == null ? 0 : n;
    }

    public void saveAll(List<StoredScore> scores) {
        jdbc.batchUpdate("""
                INSERT INTO twin.customer_risk_score (customer_id, scored_at, features_as_of, pd, churn, risk_grade,
                                                      reasons, pd_model_version, churn_model_version)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""", scores, 500, (ps, s) -> {
            ps.setLong(1, s.customerId());
            ps.setTimestamp(2, Timestamp.from(s.scoredAt()));
            ps.setTimestamp(3, Timestamp.from(s.featuresAsOf()));
            ps.setDouble(4, s.pd());
            ps.setDouble(5, s.churn());
            ps.setInt(6, s.riskGrade());
            ps.setString(7, s.reasonsJson());
            ps.setString(8, s.pdModelVersion());
            ps.setString(9, s.churnModelVersion());
        });
    }

    public Optional<StoredScore> latest(long customerId) {
        return jdbc.query("""
                SELECT customer_id, scored_at, features_as_of, pd, churn, risk_grade, reasons::text,
                       pd_model_version, churn_model_version
                FROM twin.customer_risk_score WHERE customer_id = ? ORDER BY scored_at DESC, id DESC LIMIT 1""",
                (rs, i) -> new StoredScore(rs.getLong(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant(), rs.getDouble(4), rs.getDouble(5), rs.getInt(6),
                        rs.getString(7), rs.getString(8), rs.getString(9)), customerId).stream().findFirst();
    }

    /** Aggregates of the latest portfolio scoring run: (scored_at, customers, mean pd, mean churn, versions). */
    public record RunAggregate(Instant scoredAt, long customers, double meanPd, double meanChurn,
                               String pdModelVersion, String churnModelVersion) {
    }

    public Optional<RunAggregate> latestRun() {
        return jdbc.query("""
                SELECT scored_at, COUNT(*), AVG(pd), AVG(churn), MAX(pd_model_version), MAX(churn_model_version)
                FROM twin.customer_risk_score
                WHERE scored_at = (SELECT MAX(scored_at) FROM twin.customer_risk_score
                                   WHERE scored_at IN (SELECT scored_at FROM twin.customer_risk_score
                                                       GROUP BY scored_at HAVING COUNT(*) > 1))
                GROUP BY scored_at""",
                (rs, i) -> new RunAggregate(rs.getTimestamp(1).toInstant(), rs.getLong(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getString(5), rs.getString(6))).stream().findFirst();
    }

    /** Customers per risk grade in the given scoring run. */
    public Map<Integer, Long> gradeCounts(Instant scoredAt) {
        Map<Integer, Long> out = new TreeMap<>();
        jdbc.query("SELECT risk_grade, COUNT(*) FROM twin.customer_risk_score WHERE scored_at = ? GROUP BY 1",
                rs -> {
                    out.put(rs.getInt(1), rs.getLong(2));
                }, Timestamp.from(scoredAt));
        return out;
    }

    /** Mean PD, mean churn and customers per segment in the given scoring run. */
    public Map<String, double[]> segmentAverages(Instant scoredAt) {
        Map<String, double[]> out = new TreeMap<>();
        jdbc.query("""
                SELECT s.state ->> 'segment', AVG(r.pd), AVG(r.churn), COUNT(*)
                FROM twin.customer_risk_score r JOIN twin.customer_twin_state s ON s.customer_id = r.customer_id
                WHERE r.scored_at = ? GROUP BY 1""", rs -> {
            out.put(String.valueOf(rs.getString(1)), new double[]{rs.getDouble(2), rs.getDouble(3), rs.getLong(4)});
        }, Timestamp.from(scoredAt));
        return out;
    }

    public long countScores(Instant scoredAt) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM twin.customer_risk_score WHERE scored_at = ?", Long.class,
                Timestamp.from(scoredAt));
        return n == null ? 0 : n;
    }
}

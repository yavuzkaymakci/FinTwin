package com.fintwin.portfolio.repository;

import com.fintwin.portfolio.dto.PortfolioSummary;
import com.fintwin.portfolio.dto.SegmentSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregates over the latest risk score of each active customer joined with the twin (read model). */
@Repository
public class PortfolioRepository {

    /** Latest score of each active customer with segment and twin exposure. */
    private static final String SCORED = """
            WITH latest AS (
                SELECT DISTINCT ON (customer_id) customer_id, pd, churn, risk_grade, scored_at
                FROM twin.customer_risk_score ORDER BY customer_id, scored_at DESC, id DESC)
            SELECT c.id, c.segment, c.city, l.pd, l.churn, l.risk_grade, l.scored_at, s.utilization_rate,
                   s.liquidity_buffer_months, COALESCE(s.total_debt, 0) AS total_debt
            FROM customer c JOIN latest l ON l.customer_id = c.id
            LEFT JOIN twin.customer_twin_state s ON s.customer_id = c.id
            WHERE c.status = 'ACTIVE'
            """;

    public record Totals(long activeCustomers, long scoredCustomers, double meanPd, double meanChurn,
                         BigDecimal totalDebt, BigDecimal expectedLoss, long highRisk, Instant scoredAt) {
    }

    private final JdbcTemplate jdbc;

    public PortfolioRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Totals totals(double lgd) {
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE status = 'ACTIVE'", Long.class);
        return jdbc.queryForObject("SELECT COUNT(*), COALESCE(AVG(pd), 0), COALESCE(AVG(churn), 0), "
                        + "COALESCE(SUM(total_debt), 0), COALESCE(SUM(pd * ? * total_debt), 0), "
                        + "COUNT(*) FILTER (WHERE risk_grade >= 8), MAX(scored_at) FROM (" + SCORED + ") x",
                (rs, i) -> new Totals(active == null ? 0 : active, rs.getLong(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getLong(6), instant(rs.getTimestamp(7))), lgd);
    }

    public Map<Integer, Long> grades() {
        Map<Integer, Long> out = new TreeMap<>();
        for (int g = 1; g <= 10; g++) {
            out.put(g, 0L);
        }
        jdbc.query("SELECT risk_grade, COUNT(*) FROM (" + SCORED + ") x GROUP BY 1", rs -> {
            out.put(rs.getInt(1), rs.getLong(2));
        });
        return out;
    }

    public List<SegmentSummary> segments(double lgd) {
        return jdbc.query("SELECT segment, COUNT(*), AVG(pd), AVG(churn), SUM(pd * ? * total_debt), SUM(total_debt), "
                        + "AVG(utilization_rate), AVG(LEAST(liquidity_buffer_months, 60)), "
                        + "COUNT(*) FILTER (WHERE risk_grade >= 8) FROM (" + SCORED + ") x GROUP BY 1 ORDER BY 1",
                (rs, i) -> new SegmentSummary(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getObject(7, Double.class),
                        rs.getObject(8, Double.class), rs.getLong(9)), lgd);
    }

    public List<PortfolioSummary.RiskyCustomer> topRisk(int limit) {
        return jdbc.query("SELECT * FROM (" + SCORED + ") x ORDER BY pd DESC, id LIMIT ?",
                (rs, i) -> new PortfolioSummary.RiskyCustomer(rs.getLong("id"), rs.getString("segment"),
                        rs.getString("city"), rs.getDouble("pd"), rs.getDouble("churn"), rs.getInt("risk_grade"),
                        rs.getObject("utilization_rate", Double.class), rs.getBigDecimal("total_debt")), limit);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

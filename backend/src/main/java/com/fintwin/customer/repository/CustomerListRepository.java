package com.fintwin.customer.repository;

import com.fintwin.customer.dto.CustomerListItem;
import com.fintwin.customer.dto.CustomerListQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Customer list joined with each customer's latest risk score and current twin features (read model). */
@Repository
public class CustomerListRepository {

    /** Sort keys accepted from clients mapped to SQL expressions (never interpolate client input). */
    private static final Map<String, String> SORTS = Map.of(
            "id", "c.id", "pd", "l.pd", "churn", "l.churn", "riskGrade", "l.risk_grade", "age", "c.age",
            "income", "c.monthly_net_income", "utilization", "s.utilization_rate",
            "buffer", "s.liquidity_buffer_months", "debt", "s.total_debt");

    private static final String FROM = """
            FROM customer c
            LEFT JOIN (SELECT DISTINCT ON (customer_id) customer_id, pd, churn, risk_grade, scored_at
                       FROM twin.customer_risk_score ORDER BY customer_id, scored_at DESC, id DESC) l
                   ON l.customer_id = c.id
            LEFT JOIN twin.customer_twin_state s ON s.customer_id = c.id
            """;

    private static final RowMapper<CustomerListItem> MAPPER = (rs, i) -> new CustomerListItem(rs.getLong("id"),
            rs.getString("segment"), rs.getString("city"), rs.getInt("age"), rs.getBigDecimal("monthly_net_income"),
            rs.getString("status"), rs.getDate("customer_since").toLocalDate(), rs.getObject("pd", Double.class),
            rs.getObject("churn", Double.class), rs.getObject("risk_grade", Integer.class),
            rs.getTimestamp("scored_at") == null ? null : rs.getTimestamp("scored_at").toInstant(),
            rs.getObject("utilization_rate", Double.class), rs.getObject("liquidity_buffer_months", Double.class),
            rs.getBigDecimal("total_debt"), rs.getBigDecimal("liquid_balance"));

    private final JdbcTemplate jdbc;

    public CustomerListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean validSort(String sort) {
        return sort == null || SORTS.containsKey(sort);
    }

    public List<CustomerListItem> find(CustomerListQuery q) {
        List<Object> args = new ArrayList<>();
        String where = where(q, args);
        String order = SORTS.getOrDefault(q.sort() == null ? "id" : q.sort(), "c.id");
        args.add(q.size());
        args.add((long) q.page() * q.size());
        return jdbc.query("""
                SELECT c.id, c.segment, c.city, c.age, c.monthly_net_income, c.status, c.customer_since,
                       l.pd, l.churn, l.risk_grade, l.scored_at, s.utilization_rate, s.liquidity_buffer_months,
                       s.total_debt, s.checking_balance + s.deposit_balance AS liquid_balance
                """ + FROM + where + " ORDER BY " + order + (q.descending() ? " DESC" : " ASC")
                + " NULLS LAST, c.id LIMIT ? OFFSET ?", MAPPER, args.toArray());
    }

    public long count(CustomerListQuery q) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where(q, args), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(CustomerListQuery q, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (q.search() != null && !q.search().isBlank()) {
            String s = q.search().trim();
            if (s.matches("[0-9]{1,18}")) {
                conditions.add("c.id = ?");
                args.add(Long.parseLong(s));
            } else {
                conditions.add("c.city ILIKE ?");
                args.add("%" + s.replace("%", "").replace("_", "") + "%");
            }
        }
        if (q.segment() != null && !q.segment().isBlank()) {
            conditions.add("c.segment = ?");
            args.add(q.segment());
        }
        if (q.status() != null && !q.status().isBlank()) {
            conditions.add("c.status = ?");
            args.add(q.status());
        }
        if (q.minGrade() != null) {
            conditions.add("l.risk_grade >= ?");
            args.add(q.minGrade());
        }
        if (q.maxGrade() != null) {
            conditions.add("l.risk_grade <= ?");
            args.add(q.maxGrade());
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }
}

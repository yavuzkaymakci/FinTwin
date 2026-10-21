package com.fintwin.feed.publish;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.events.EventCodec;
import com.fintwin.events.v1.AccountClosedV1;
import com.fintwin.events.v1.AccountOpenedV1;
import com.fintwin.events.v1.CardStatementIssuedV1;
import com.fintwin.events.v1.CustomerRegisteredV1;
import com.fintwin.events.v1.LoanDisbursedV1;
import com.fintwin.events.v1.LoanInstallmentSettledV1;
import com.fintwin.events.v1.PaymentPastDueV1;
import com.fintwin.events.v1.TransactionRecordedV1;
import com.fintwin.feed.outbox.OutboxKind;
import com.fintwin.feed.outbox.OutboxRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns outbox rows into versioned events by loading their source rows from the core banking tables, one query per
 * kind and page.
 */
@Component
public class EventAssembler {

    @FunctionalInterface
    private interface Builder {
        CoreBankingEvent build(OutboxRow row, ResultSet rs) throws SQLException;
    }

    private record Loader(String sql, Builder builder) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<OutboxKind, Loader> loaders = new EnumMap<>(OutboxKind.class);
    private volatile String epoch;
    private volatile LocalDate feedStart;

    public EventAssembler(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        loaders.put(OutboxKind.CUSTOMER_REGISTERED, new Loader("""
                SELECT id AS source_id, customer_since, segment, age, city, occupation_group
                FROM customer WHERE id IN (:ids)""", (row, rs) -> new CustomerRegisteredV1(id(row),
                CustomerRegisteredV1.SCHEMA_VERSION, row.occurredAt(), row.customerId(), row.customerSequence(),
                date(rs, "customer_since"), rs.getString("segment"), rs.getInt("age"), rs.getString("city"),
                rs.getString("occupation_group"))));
        loaders.put(OutboxKind.ACCOUNT_OPENED, new Loader("""
                SELECT id AS source_id, account_type, currency, opened_at, opening_balance, credit_limit,
                       statement_day, due_day, interest_rate
                FROM account WHERE id IN (:ids)""", (row, rs) -> new AccountOpenedV1(id(row),
                AccountOpenedV1.SCHEMA_VERSION, row.occurredAt(), row.customerId(), row.customerSequence(),
                row.sourceId(), rs.getString("account_type"), rs.getString("currency"), date(rs, "opened_at"),
                rs.getBigDecimal("opening_balance"), rs.getBigDecimal("credit_limit"),
                (Integer) rs.getObject("statement_day"), (Integer) rs.getObject("due_day"),
                rs.getBigDecimal("interest_rate"))));
        loaders.put(OutboxKind.ACCOUNT_CLOSED, new Loader("""
                SELECT id AS source_id, closed_at FROM account WHERE id IN (:ids)""",
                (row, rs) -> new AccountClosedV1(id(row), AccountClosedV1.SCHEMA_VERSION, row.occurredAt(),
                        row.customerId(), row.customerSequence(), row.sourceId(), date(rs, "closed_at"))));
        loaders.put(OutboxKind.TRANSACTION_RECORDED, new Loader("""
                SELECT t.id AS source_id, t.account_id, t.amount, t.currency, t.direction, t.channel,
                       t.installment_count, t.raw_description, t.merchant_id, m.name AS merchant_name,
                       mc.code AS merchant_category, cp.account_id AS counterpart_account_id
                FROM bank_transaction t
                LEFT JOIN merchant m ON m.id = t.merchant_id
                LEFT JOIN merchant_category mc ON mc.id = m.category_id
                LEFT JOIN bank_transaction cp ON cp.id = t.counterpart_transaction_id
                WHERE t.id IN (:ids)""", (row, rs) -> new TransactionRecordedV1(id(row),
                TransactionRecordedV1.SCHEMA_VERSION, row.occurredAt(), row.customerId(), row.customerSequence(),
                row.sourceId(), rs.getLong("account_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("direction"), rs.getString("channel"), rs.getInt("installment_count"),
                rs.getString("raw_description"), (Long) rs.getObject("merchant_id"), rs.getString("merchant_name"),
                rs.getString("merchant_category"), (Long) rs.getObject("counterpart_account_id"))));
        loaders.put(OutboxKind.CARD_STATEMENT_ISSUED, new Loader("""
                SELECT id AS source_id, account_id, statement_date, due_date, statement_balance, minimum_payment
                FROM card_statement WHERE id IN (:ids)""", (row, rs) -> new CardStatementIssuedV1(id(row),
                CardStatementIssuedV1.SCHEMA_VERSION, row.occurredAt(), row.customerId(), row.customerSequence(),
                row.sourceId(), rs.getLong("account_id"), date(rs, "statement_date"), date(rs, "due_date"),
                rs.getBigDecimal("statement_balance"), rs.getBigDecimal("minimum_payment"))));
        loaders.put(OutboxKind.CARD_PAYMENT_PAST_DUE, new Loader("""
                SELECT id AS source_id, account_id, due_date, minimum_payment
                FROM card_statement WHERE id IN (:ids)""", (row, rs) -> new PaymentPastDueV1(id(row),
                PaymentPastDueV1.SCHEMA_VERSION, row.occurredAt(), row.customerId(), row.customerSequence(),
                "CARD_STATEMENT", row.sourceId(), rs.getLong("account_id"), null, date(rs, "due_date"),
                rs.getBigDecimal("minimum_payment"))));
        loaders.put(OutboxKind.LOAN_PAYMENT_PAST_DUE, new Loader("""
                SELECT id AS source_id, loan_id, due_date, total_amount
                FROM loan_installment WHERE id IN (:ids)""", (row, rs) -> new PaymentPastDueV1(id(row),
                PaymentPastDueV1.SCHEMA_VERSION, row.occurredAt(), row.customerId(), row.customerSequence(),
                "LOAN_INSTALLMENT", row.sourceId(), null, rs.getLong("loan_id"), date(rs, "due_date"),
                rs.getBigDecimal("total_amount"))));
        loaders.put(OutboxKind.LOAN_INSTALLMENT_SETTLED, new Loader("""
                SELECT id AS source_id, loan_id, installment_no, due_date, paid_at, principal_amount,
                       interest_amount, total_amount, remaining_principal, days_past_due
                FROM loan_installment WHERE id IN (:ids)""", (row, rs) -> new LoanInstallmentSettledV1(id(row),
                LoanInstallmentSettledV1.SCHEMA_VERSION, row.occurredAt(), row.customerId(), row.customerSequence(),
                rs.getLong("loan_id"), rs.getInt("installment_no"), date(rs, "due_date"), date(rs, "paid_at"),
                rs.getBigDecimal("principal_amount"), rs.getBigDecimal("interest_amount"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("remaining_principal"),
                rs.getInt("days_past_due"))));
        // Position of a loan when it enters the feed: installments settled before the feed start are summarised.
        loaders.put(OutboxKind.LOAN_DISBURSED, new Loader("""
                SELECT l.id AS source_id, l.loan_type, l.principal, l.monthly_interest_rate, l.term_months,
                       l.disbursed_at,
                       (SELECT i.total_amount FROM loan_installment i WHERE i.loan_id = l.id AND i.installment_no = 1)
                           AS installment_amount,
                       (SELECT COUNT(*) FROM loan_installment i
                        WHERE i.loan_id = l.id AND i.paid_at < CAST(:feedStart AS date)) AS paid_installments,
                       l.principal - (SELECT COALESCE(SUM(i.principal_amount), 0) FROM loan_installment i
                        WHERE i.loan_id = l.id AND i.paid_at < CAST(:feedStart AS date)) AS outstanding
                FROM loan l WHERE l.id IN (:ids)""", (row, rs) -> new LoanDisbursedV1(id(row),
                LoanDisbursedV1.SCHEMA_VERSION, row.occurredAt(), row.customerId(), row.customerSequence(),
                row.sourceId(), rs.getString("loan_type"), rs.getBigDecimal("principal"),
                rs.getBigDecimal("monthly_interest_rate"), rs.getInt("term_months"), date(rs, "disbursed_at"),
                rs.getBigDecimal("installment_amount"), rs.getInt("paid_installments"),
                rs.getBigDecimal("outstanding"))));
    }

    /** Must be called before assembling: scopes event ids to the prepared data set. */
    public void configure(String datasetEpoch, LocalDate feedStartDate) {
        this.epoch = datasetEpoch;
        this.feedStart = feedStartDate;
    }

    /** Events for {@code rows}, in the same order. */
    public List<CoreBankingEvent> assemble(List<OutboxRow> rows) {
        Map<OutboxKind, Map<Long, OutboxRow>> byKind = new EnumMap<>(OutboxKind.class);
        for (OutboxRow row : rows) {
            byKind.computeIfAbsent(row.kind(), k -> new HashMap<>()).put(row.sourceId(), row);
        }
        Map<OutboxRow, CoreBankingEvent> built = new HashMap<>(rows.size() * 2);
        for (Map.Entry<OutboxKind, Map<Long, OutboxRow>> e : byKind.entrySet()) {
            Loader loader = loaders.get(e.getKey());
            Map<Long, OutboxRow> bySource = e.getValue();
            MapSqlParameterSource params = new MapSqlParameterSource("ids", bySource.keySet())
                    .addValue("feedStart", feedStart);
            jdbc.query(loader.sql(), params, rs -> {
                OutboxRow row = bySource.get(rs.getLong("source_id"));
                built.put(row, loader.builder().build(row, rs));
            });
        }
        List<CoreBankingEvent> events = new ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            CoreBankingEvent event = built.get(row);
            if (event == null) {
                throw new IllegalStateException("Source row missing for outbox position " + row.position());
            }
            events.add(event);
        }
        return events;
    }

    private UUID id(OutboxRow row) {
        return EventCodec.eventId(epoch, row.kind().name(), row.sourceId());
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}

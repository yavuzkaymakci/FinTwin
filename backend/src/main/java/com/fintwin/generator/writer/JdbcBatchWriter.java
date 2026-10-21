package com.fintwin.generator.writer;

import com.fintwin.generator.catalog.MerchantCatalog.MerchantRef;
import com.fintwin.generator.simulation.Sim;
import com.fintwin.generator.simulation.Sim.CustomerHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk-loads generated data with {@link JdbcTemplate} batch inserts (JPA {@code saveAll} would be orders of
 * magnitude slower for millions of rows). Ids are assigned here, sequentially in write order, which keeps them
 * deterministic for a given seed.
 */
@Component
public class JdbcBatchWriter {

    /** Generated tables in reverse dependency order; reference data (categories, macro) is never touched. */
    static final List<String> GENERATED_TABLES = List.of(
            "ground_truth.transaction_label", "ground_truth.customer_monthly_state",
            "ground_truth.customer_outcome", "ground_truth.customer_latent",
            "customer_event", "bank_transaction", "card_statement", "loan_installment", "loan", "account",
            "merchant", "customer");

    private static final List<String> IDENTITY_TABLES = List.of(
            "customer", "account", "card_statement", "loan", "loan_installment", "merchant", "bank_transaction",
            "customer_event");

    private static final String INSERT_CUSTOMER = """
            INSERT INTO customer (id, age, city, occupation_group, segment, monthly_net_income, customer_since, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_ACCOUNT = """
            INSERT INTO account (id, customer_id, account_type, currency, balance, opened_at, closed_at,
                                 interest_rate, maturity_date, credit_limit, statement_day, due_day)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_STATEMENT = """
            INSERT INTO card_statement (id, account_id, statement_date, due_date, statement_balance, minimum_payment,
                                        paid_amount, paid_at, days_past_due)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_LOAN = """
            INSERT INTO loan (id, customer_id, loan_type, principal, monthly_interest_rate, term_months, disbursed_at,
                              status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_INSTALLMENT = """
            INSERT INTO loan_installment (id, loan_id, installment_no, due_date, principal_amount, interest_amount,
                                          total_amount, remaining_principal, paid_amount, paid_at, days_past_due)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_MERCHANT = """
            INSERT INTO merchant (id, name, category_id, city) VALUES (?, ?, ?, ?)""";
    private static final String INSERT_TRANSACTION = """
            INSERT INTO bank_transaction (id, account_id, customer_id, merchant_id, amount, currency, direction,
                                          channel, installment_count, raw_description, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_EVENT = """
            INSERT INTO customer_event (id, customer_id, event_type, event_date, account_id, loan_id, days_past_due,
                                        details)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_LABEL = """
            INSERT INTO ground_truth.transaction_label (transaction_id, category_code) VALUES (?, ?)""";
    private static final String INSERT_LATENT = """
            INSERT INTO ground_truth.customer_latent (customer_id, resilience, churn_propensity, payer_type,
                spending_style, income_volatility_base, raise_pass_through, salary_day, base_monthly_income, has_car,
                is_renter)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_MONTHLY_STATE = """
            INSERT INTO ground_truth.customer_monthly_state (customer_id, period, financial_stress, debt_to_income,
                real_income_loss, card_utilization, income_volatility, liquidity_buffer_months, monthly_income,
                p_miss_payment, p_churn, days_past_due, unemployed, churn_intent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_OUTCOME = """
            INSERT INTO ground_truth.customer_outcome (customer_id, defaulted, default_date, churned, churn_date,
                late_payment_count, unemployment_months, max_stress, mean_stress)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    /** Next id per entity, continued across chunks. */
    public static final class IdSequence {
        long account = 1;
        long statement = 1;
        long loan = 1;
        long installment = 1;
        long transaction = 1;
        long event = 1;
    }

    private final JdbcTemplate jdbc;

    public JdbcBatchWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void truncateGeneratedTables() {
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", GENERATED_TABLES) + " RESTART IDENTITY CASCADE");
    }

    public long countCustomers() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM customer", Long.class);
        return count == null ? 0 : count;
    }

    public long writeMerchants(List<MerchantRef> merchants, int batchSize) {
        batch(INSERT_MERCHANT, merchants, batchSize, (ps, m) -> {
            ps.setLong(1, m.id());
            ps.setString(2, m.name());
            ps.setInt(3, m.category().id());
            ps.setString(4, m.city().name());
        });
        return merchants.size();
    }

    /** A chunk whose entities already carry their database ids. */
    public record PreparedChunk(List<CustomerHistory> histories, long firstEventId) {
    }

    /**
     * Assigns ids to one chunk. Must be called sequentially in chunk order so that ids are deterministic; the
     * prepared chunks can then be written concurrently.
     */
    public PreparedChunk assignIds(List<CustomerHistory> chunk, IdSequence ids) {
        long firstEventId = ids.event;
        for (CustomerHistory h : chunk) {
            for (Sim.Account a : h.accounts) {
                a.id = ids.account++;
            }
            for (Sim.Statement s : h.statements) {
                s.id = ids.statement++;
            }
            for (Sim.Loan l : h.loans) {
                l.id = ids.loan++;
                for (Sim.Installment i : l.installments) {
                    i.id = ids.installment++;
                }
            }
            for (Sim.Transaction t : h.transactions) {
                t.id = ids.transaction++;
            }
            ids.event += h.events.size();
        }
        return new PreparedChunk(chunk, firstEventId);
    }

    /**
     * Writes one prepared chunk. Must run inside a transaction; chunks are independent and may be written in
     * parallel on separate connections.
     *
     * @return rows written per table
     */
    public Map<String, Long> writeChunk(PreparedChunk prepared, int batchSize) {
        List<CustomerHistory> chunk = prepared.histories();
        List<Sim.Account> accounts = new ArrayList<>();
        List<Long> accountOwners = new ArrayList<>();
        List<Sim.Statement> statements = new ArrayList<>();
        List<Sim.Loan> loans = new ArrayList<>();
        List<Long> loanOwners = new ArrayList<>();
        List<Sim.Installment> installments = new ArrayList<>();
        List<Long> installmentLoans = new ArrayList<>();
        List<Sim.Transaction> transactions = new ArrayList<>();
        List<Long> transactionOwners = new ArrayList<>();
        List<Sim.Event> events = new ArrayList<>();
        List<Long> eventOwners = new ArrayList<>();
        List<Sim.MonthlyState> states = new ArrayList<>();
        List<Long> stateOwners = new ArrayList<>();

        for (CustomerHistory h : chunk) {
            long customerId = h.customerId();
            for (Sim.Account a : h.accounts) {
                accounts.add(a);
                accountOwners.add(customerId);
            }
            statements.addAll(h.statements);
            for (Sim.Loan l : h.loans) {
                loans.add(l);
                loanOwners.add(customerId);
                for (Sim.Installment i : l.installments) {
                    installments.add(i);
                    installmentLoans.add(l.id);
                }
            }
            for (Sim.Transaction t : h.transactions) {
                transactions.add(t);
                transactionOwners.add(customerId);
            }
            for (Sim.Event e : h.events) {
                events.add(e);
                eventOwners.add(customerId);
            }
            for (Sim.MonthlyState s : h.monthlyStates) {
                states.add(s);
                stateOwners.add(customerId);
            }
        }

        batch(INSERT_CUSTOMER, chunk, batchSize, (ps, h) -> {
            ps.setLong(1, h.customerId());
            ps.setInt(2, h.age);
            ps.setString(3, h.city);
            ps.setString(4, h.occupation.name());
            ps.setString(5, h.segment.name());
            ps.setBigDecimal(6, Sim.toMoney(h.latestIncomeCents));
            ps.setObject(7, h.customerSince);
            ps.setString(8, h.status.name());
        });
        batchIndexed(INSERT_ACCOUNT, accounts, batchSize, (ps, a, i) -> {
            ps.setLong(1, a.id);
            ps.setLong(2, accountOwners.get(i));
            ps.setString(3, a.type.name());
            ps.setString(4, a.currency.name());
            ps.setBigDecimal(5, Sim.toMoney(a.balanceCents));
            ps.setObject(6, a.openedAt);
            setDate(ps, 7, a.closedAt);
            ps.setBigDecimal(8, a.interestRate);
            setDate(ps, 9, a.maturityDate);
            ps.setBigDecimal(10, a.statementDay == null ? null : Sim.toMoney(a.creditLimitCents));
            setInteger(ps, 11, a.statementDay);
            setInteger(ps, 12, a.dueDay);
        });
        batch(INSERT_STATEMENT, statements, batchSize, (ps, s) -> {
            ps.setLong(1, s.id);
            ps.setLong(2, s.card.id);
            ps.setObject(3, s.statementDate);
            ps.setObject(4, s.dueDate);
            ps.setBigDecimal(5, Sim.toMoney(s.balanceCents));
            ps.setBigDecimal(6, Sim.toMoney(s.minimumPaymentCents));
            ps.setBigDecimal(7, Sim.toMoney(s.paidCents));
            setDate(ps, 8, s.paidAt);
            ps.setInt(9, s.daysPastDue);
        });
        batchIndexed(INSERT_LOAN, loans, batchSize, (ps, l, i) -> {
            ps.setLong(1, l.id);
            ps.setLong(2, loanOwners.get(i));
            ps.setString(3, l.type.name());
            ps.setBigDecimal(4, l.principal);
            ps.setBigDecimal(5, l.monthlyRate);
            ps.setInt(6, l.termMonths);
            ps.setObject(7, l.disbursedAt);
            ps.setString(8, l.status.name());
        });
        batchIndexed(INSERT_INSTALLMENT, installments, batchSize, (ps, inst, i) -> {
            ps.setLong(1, inst.id);
            ps.setLong(2, installmentLoans.get(i));
            ps.setInt(3, inst.installmentNo);
            ps.setObject(4, inst.dueDate);
            ps.setBigDecimal(5, inst.principal);
            ps.setBigDecimal(6, inst.interest);
            ps.setBigDecimal(7, inst.principal.add(inst.interest));
            ps.setBigDecimal(8, inst.remainingPrincipal);
            ps.setBigDecimal(9, Sim.toMoney(inst.paidCents));
            setDate(ps, 10, inst.paidAt);
            ps.setInt(11, inst.daysPastDue);
        });
        batchIndexed(INSERT_TRANSACTION, transactions, batchSize, (ps, t, i) -> {
            ps.setLong(1, t.id);
            ps.setLong(2, t.account.id);
            ps.setLong(3, transactionOwners.get(i));
            if (t.merchant == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, t.merchant.id());
            }
            ps.setBigDecimal(5, Sim.toMoney(t.amountCents));
            ps.setString(6, t.account.currency.name());
            ps.setString(7, t.direction.name());
            ps.setString(8, t.channel.name());
            ps.setInt(9, t.installmentCount);
            ps.setString(10, t.description);
            ps.setObject(11, t.occurredAt);
        });
        batchIndexed(INSERT_EVENT, events, batchSize, (ps, e, i) -> {
            ps.setLong(1, prepared.firstEventId() + i);
            ps.setLong(2, eventOwners.get(i));
            ps.setString(3, e.type().name());
            ps.setObject(4, e.date());
            if (e.account() == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, e.account().id);
            }
            if (e.loan() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, e.loan().id);
            }
            setInteger(ps, 7, e.daysPastDue());
            ps.setString(8, e.details());
        });
        batch(INSERT_LABEL, transactions, batchSize, (ps, t) -> {
            ps.setLong(1, t.id);
            ps.setString(2, t.category.code());
        });
        batch(INSERT_LATENT, chunk, batchSize, (ps, h) -> {
            Sim.Latent l = h.latent;
            ps.setLong(1, h.customerId());
            ps.setDouble(2, l.resilience());
            ps.setDouble(3, l.churnPropensity());
            ps.setString(4, l.payerType().name());
            ps.setDouble(5, l.spendingStyle());
            ps.setDouble(6, l.incomeVolatilityBase());
            ps.setDouble(7, l.raisePassThrough());
            setInteger(ps, 8, l.salaryDay());
            ps.setBigDecimal(9, Sim.toMoney(l.baseIncomeCents()));
            ps.setBoolean(10, l.hasCar());
            ps.setBoolean(11, l.renter());
        });
        batchIndexed(INSERT_MONTHLY_STATE, states, batchSize, (ps, s, i) -> {
            ps.setLong(1, stateOwners.get(i));
            ps.setObject(2, s.month().atDay(1));
            ps.setDouble(3, s.stress());
            ps.setDouble(4, s.debtToIncome());
            ps.setDouble(5, s.realIncomeLoss());
            ps.setDouble(6, s.cardUtilization());
            ps.setDouble(7, s.incomeVolatility());
            ps.setDouble(8, s.bufferMonths());
            ps.setBigDecimal(9, Sim.toMoney(s.incomeCents()));
            ps.setDouble(10, s.pMissPayment());
            ps.setDouble(11, s.pChurn());
            ps.setInt(12, s.daysPastDue());
            ps.setBoolean(13, s.unemployed());
            ps.setBoolean(14, s.churnIntent());
        });
        batch(INSERT_OUTCOME, chunk, batchSize, (ps, h) -> {
            Sim.Outcome o = h.outcome;
            ps.setLong(1, h.customerId());
            ps.setBoolean(2, o.defaulted());
            setDate(ps, 3, o.defaultDate());
            ps.setBoolean(4, o.churned());
            setDate(ps, 5, o.churnDate());
            ps.setInt(6, o.latePaymentCount());
            ps.setInt(7, o.unemploymentMonths());
            ps.setDouble(8, o.maxStress());
            ps.setDouble(9, o.meanStress());
        });

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("customer", (long) chunk.size());
        counts.put("account", (long) accounts.size());
        counts.put("card_statement", (long) statements.size());
        counts.put("loan", (long) loans.size());
        counts.put("loan_installment", (long) installments.size());
        counts.put("bank_transaction", (long) transactions.size());
        counts.put("customer_event", (long) events.size());
        counts.put("ground_truth.transaction_label", (long) transactions.size());
        counts.put("ground_truth.customer_latent", (long) chunk.size());
        counts.put("ground_truth.customer_monthly_state", (long) states.size());
        counts.put("ground_truth.customer_outcome", (long) chunk.size());
        return counts;
    }

    /** Moves identity sequences past the explicitly inserted ids and refreshes planner statistics. */
    public void finish() {
        for (String table : IDENTITY_TABLES) {
            jdbc.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), "
                    + "COALESCE((SELECT MAX(id) FROM " + table + "), 0) + 1, false)");
        }
        jdbc.execute("ANALYZE");
    }

    public void recordRun(long seed, int customers, int months, LocalDate endMonth, Instant startedAt,
                          long durationMs, String rowCounts) {
        jdbc.update("""
                        INSERT INTO ground_truth.generation_run (seed, customer_count, months, end_month, started_at,
                                                                 duration_ms, row_counts)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                seed, customers, months, endMonth, Timestamp.from(startedAt), durationMs, rowCounts);
    }

    @FunctionalInterface
    private interface IndexedSetter<T> {
        void set(PreparedStatement ps, T item, int index) throws SQLException;
    }

    private <T> void batch(String sql, List<T> items, int batchSize, ParameterizedPreparedStatementSetter<T> setter) {
        if (!items.isEmpty()) {
            jdbc.batchUpdate(sql, items, batchSize, setter);
        }
    }

    /** Batch insert where the setter also needs the item's position (for parallel owner-id lists). */
    private <T> void batchIndexed(String sql, List<T> items, int batchSize, IndexedSetter<T> setter) {
        if (items.isEmpty()) {
            return;
        }
        List<Integer> positions = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            positions.add(i);
        }
        jdbc.batchUpdate(sql, positions, batchSize, (ps, i) -> setter.set(ps, items.get(i), i));
    }

    private static void setDate(PreparedStatement ps, int index, LocalDate date) throws SQLException {
        if (date == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setObject(index, date);
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}

package com.fintwin.twin.store;

import com.fintwin.twin.projection.TwinFeatures;
import com.fintwin.twin.projection.TwinJson;
import com.fintwin.twin.projection.TwinState;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Twin projection rows ({@code twin.customer_twin_state}) and monthly snapshots, with optimistic locking. */
@Repository
public class TwinStateRepository {

    /** A loaded projection row; {@code version} is the optimistic-lock token. */
    public record VersionedState(long customerId, long version, long lastSequence, TwinState state,
                                 String stateJson, String featuresJson) {
    }

    /** A projection to persist; {@code expectedVersion} is null for a customer seen for the first time. */
    public record StateWrite(TwinState state, TwinFeatures features, Long expectedVersion) {
    }

    public record Snapshot(long customerId, LocalDate asOf, long lastSequence, String stateJson,
                           String featuresJson) {
    }

    private static final String INSERT = """
            INSERT INTO twin.customer_twin_state (customer_id, version, last_seq, last_event_at, state, features,
                checking_balance, deposit_balance, card_debt, total_debt, utilization_rate, spend_30d, spend_90d,
                income_estimate, monthly_obligations, debt_to_income, late_payment_count,
                days_since_last_late_payment, liquidity_buffer_months)
            VALUES (?, 1, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (customer_id) DO NOTHING""";

    private static final String UPDATE = """
            UPDATE twin.customer_twin_state SET version = version + 1, last_seq = ?, last_event_at = ?,
                state = ?::jsonb, features = ?::jsonb, checking_balance = ?, deposit_balance = ?, card_debt = ?,
                total_debt = ?, utilization_rate = ?, spend_30d = ?, spend_90d = ?, income_estimate = ?,
                monthly_obligations = ?, debt_to_income = ?, late_payment_count = ?,
                days_since_last_late_payment = ?, liquidity_buffer_months = ?, updated_at = now()
            WHERE customer_id = ? AND version = ?""";

    private final JdbcTemplate jdbc;

    public TwinStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, VersionedState> load(Collection<Long> customerIds) {
        Map<Long, VersionedState> result = new HashMap<>();
        if (customerIds.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT customer_id, version, last_seq, state::text, features::text FROM twin.customer_twin_state
                    WHERE customer_id = ANY(?)""");
            ps.setArray(1, con.createArrayOf("bigint", customerIds.toArray()));
            return ps;
        }, (RowCallbackHandler) rs -> {
            String json = rs.getString(4);
            result.put(rs.getLong(1), new VersionedState(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                    TwinJson.readState(json), json, rs.getString(5)));
        });
        return result;
    }

    public Optional<VersionedState> find(long customerId) {
        return Optional.ofNullable(load(List.of(customerId)).get(customerId));
    }

    /**
     * Inserts new and updates existing projections in batches.
     *
     * @throws OptimisticLockingFailureException if any row was changed concurrently (whole batch must roll back)
     */
    public void save(List<StateWrite> writes) {
        List<StateWrite> inserts = new ArrayList<>();
        List<StateWrite> updates = new ArrayList<>();
        for (StateWrite w : writes) {
            (w.expectedVersion() == null ? inserts : updates).add(w);
        }
        if (!inserts.isEmpty()) {
            int[][] counts = jdbc.batchUpdate(INSERT, inserts, 1000, (ps, w) -> {
                ps.setLong(1, w.state().customerId);
                ps.setLong(2, w.state().lastSequence);
                ps.setTimestamp(3, Timestamp.from(w.state().lastEventAt));
                ps.setString(4, TwinJson.write(w.state()));
                ps.setString(5, TwinJson.write(w.features()));
                setFeatureColumns(ps, 6, w.features());
            });
            verify(counts, "inserted concurrently");
        }
        if (!updates.isEmpty()) {
            int[][] counts = jdbc.batchUpdate(UPDATE, updates, 1000, (ps, w) -> {
                ps.setLong(1, w.state().lastSequence);
                ps.setTimestamp(2, Timestamp.from(w.state().lastEventAt));
                ps.setString(3, TwinJson.write(w.state()));
                ps.setString(4, TwinJson.write(w.features()));
                setFeatureColumns(ps, 5, w.features());
                ps.setLong(18, w.state().customerId);
                ps.setLong(19, w.expectedVersion());
            });
            verify(counts, "modified concurrently");
        }
    }

    public void saveSnapshots(List<Snapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO twin.customer_twin_snapshot (customer_id, as_of, last_seq, state, features)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb)""", snapshots, 1000, (ps, s) -> {
            ps.setLong(1, s.customerId());
            ps.setObject(2, s.asOf());
            ps.setLong(3, s.lastSequence());
            ps.setString(4, s.stateJson());
            ps.setString(5, s.featuresJson());
        });
    }

    /** Latest snapshot with {@code as_of <= date}. */
    public Optional<Snapshot> latestSnapshotOnOrBefore(long customerId, LocalDate date) {
        return jdbc.query("""
                        SELECT customer_id, as_of, last_seq, state::text, features::text
                        FROM twin.customer_twin_snapshot WHERE customer_id = ? AND as_of <= ?
                        ORDER BY as_of DESC LIMIT 1""", (rs, i) -> new Snapshot(rs.getLong(1),
                        rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getString(4), rs.getString(5)),
                customerId, date).stream().findFirst();
    }

    /** Monthly series of one feature from the snapshots ({@code feature} must be validated by the caller). */
    public List<Map.Entry<LocalDate, Object>> featureSeries(long customerId, String feature) {
        return jdbc.query("""
                        SELECT as_of, features -> ? FROM twin.customer_twin_snapshot
                        WHERE customer_id = ? ORDER BY as_of""",
                (rs, i) -> Map.entry(rs.getObject(1, LocalDate.class), jsonValue(rs.getString(2))),
                feature, customerId);
    }

    public Map<Long, List<Snapshot>> snapshotsOf(Collection<Long> customerIds) {
        Map<Long, List<Snapshot>> result = new HashMap<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT customer_id, as_of, last_seq, state::text, features::text
                    FROM twin.customer_twin_snapshot WHERE customer_id = ANY(?) ORDER BY customer_id, as_of""");
            ps.setArray(1, con.createArrayOf("bigint", customerIds.toArray()));
            return ps;
        }, (RowCallbackHandler) rs -> result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                .add(new Snapshot(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getString(4),
                        rs.getString(5))));
        return result;
    }

    public long countStates() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM twin.customer_twin_state", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Events applied to all twins. Per-customer sequences start at 1 without gaps, so this equals the event-store
     * row count but reads one row per customer instead of millions.
     */
    public long appliedEvents() {
        Long n = jdbc.queryForObject("SELECT COALESCE(SUM(last_seq), 0) FROM twin.customer_twin_state", Long.class);
        return n == null ? 0 : n;
    }

    public long countSnapshots() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM twin.customer_twin_snapshot", Long.class);
        return n == null ? 0 : n;
    }

    public List<Long> customerIds() {
        return jdbc.queryForList("SELECT customer_id FROM twin.customer_twin_state ORDER BY customer_id", Long.class);
    }

    public void truncateProjection() {
        jdbc.execute("TRUNCATE TABLE twin.customer_twin_state, twin.customer_twin_snapshot");
    }

    private static Object jsonValue(String json) {
        if (json == null || "null".equals(json)) {
            return null;
        }
        return TwinJson.read(json, Object.class);
    }

    private static void verify(int[][] counts, String what) {
        for (int[] batch : counts) {
            for (int c : batch) {
                if (c == 0) {
                    throw new OptimisticLockingFailureException("Twin state " + what);
                }
            }
        }
    }

    private static void setFeatureColumns(PreparedStatement ps, int start, TwinFeatures f) throws SQLException {
        int i = start;
        ps.setBigDecimal(i++, f.checkingBalance());
        ps.setBigDecimal(i++, f.depositBalance());
        ps.setBigDecimal(i++, f.cardDebt());
        ps.setBigDecimal(i++, f.totalDebt());
        setDouble(ps, i++, f.utilizationRate());
        ps.setBigDecimal(i++, f.spend30d());
        ps.setBigDecimal(i++, f.spend90d());
        ps.setBigDecimal(i++, f.incomeEstimate());
        ps.setBigDecimal(i++, f.monthlyObligations());
        setDouble(ps, i++, f.debtToIncome());
        ps.setInt(i++, f.latePaymentCount());
        if (f.daysSinceLastLatePayment() == null) {
            ps.setNull(i++, Types.INTEGER);
        } else {
            ps.setInt(i++, f.daysSinceLastLatePayment());
        }
        setDouble(ps, i, f.liquidityBufferMonths());
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }
}

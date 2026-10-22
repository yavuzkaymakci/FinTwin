package com.fintwin.simulation.repository;

import com.fintwin.simulation.domain.RunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Simulation runs and their per-customer forecasts ({@code simulation.simulation_run}, {@code customer_forecast}). */
@Repository
public class SimulationRunRepository {

    public record Run(long id, long scenarioId, String scenarioCode, String scenarioName, Long baselineScenarioId,
                      LocalDate startDate, RunStatus status, double progress, String mlJobId, Instant requestedAt,
                      Instant startedAt, Instant finishedAt, String error, String resultJson, Double durationSeconds,
                      Double peakRssMb) {
    }

    public record Forecast(long customerId, long runId, String scenarioCode, String scenarioName, LocalDate startDate,
                           String forecastJson) {
    }

    private static final String SELECT = """
            SELECT r.id, r.scenario_id, s.code, s.name, r.baseline_scenario_id, r.start_date, r.status, r.progress,
                   r.ml_job_id, r.requested_at, r.started_at, r.finished_at, r.error, r.result::text,
                   r.duration_seconds, r.peak_rss_mb
            FROM simulation.simulation_run r JOIN simulation.scenario s ON s.id = r.scenario_id
            """;
    private static final RowMapper<Run> MAPPER = (rs, i) -> new Run(rs.getLong(1), rs.getLong(2), rs.getString(3),
            rs.getString(4), rs.getObject(5, Long.class), toLocalDate(rs.getDate(6)), RunStatus.valueOf(rs.getString(7)),
            rs.getDouble(8), rs.getString(9), toInstant(rs.getTimestamp(10)), toInstant(rs.getTimestamp(11)),
            toInstant(rs.getTimestamp(12)), rs.getString(13), rs.getString(14), rs.getObject(15, Double.class),
            rs.getObject(16, Double.class));

    private final JdbcTemplate jdbc;

    public SimulationRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long scenarioId, Long baselineScenarioId, LocalDate startDate, String parametersJson,
                       String baselineParametersJson, String assumptionsJson) {
        Long id = jdbc.queryForObject("""
                INSERT INTO simulation.simulation_run (scenario_id, baseline_scenario_id, start_date, parameters,
                                                       baseline_parameters, assumptions, status)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, 'PENDING') RETURNING id""", Long.class,
                scenarioId, baselineScenarioId, startDate == null ? null : Date.valueOf(startDate), parametersJson,
                baselineParametersJson, assumptionsJson);
        return id == null ? 0 : id;
    }

    public Optional<Run> findById(long id) {
        return jdbc.query(SELECT + " WHERE r.id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Run> findByIds(List<Long> ids) {
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }

    /** Runs submitted to the ML service and not finished yet, oldest first. */
    public List<Run> findActive() {
        return jdbc.query(SELECT + " WHERE r.status IN ('QUEUED', 'RUNNING') AND r.ml_job_id IS NOT NULL "
                + "ORDER BY r.id", MAPPER);
    }

    public void markSubmitted(long id, String mlJobId, RunStatus status) {
        jdbc.update("UPDATE simulation.simulation_run SET ml_job_id = ?, status = ? WHERE id = ?", mlJobId,
                status.name(), id);
    }

    public void updateProgress(long id, RunStatus status, double progress, Instant startedAt) {
        jdbc.update("UPDATE simulation.simulation_run SET status = ?, progress = ?, started_at = ? WHERE id = ?",
                status.name(), progress, timestamp(startedAt), id);
    }

    public void succeed(long id, String resultJson, Double durationSeconds, Double peakRssMb, Instant startedAt,
                        Instant finishedAt) {
        jdbc.update("""
                UPDATE simulation.simulation_run
                SET status = 'SUCCEEDED', progress = 100, result = ?::jsonb, duration_seconds = ?, peak_rss_mb = ?,
                    started_at = ?, finished_at = ?
                WHERE id = ?""", resultJson, durationSeconds, peakRssMb, timestamp(startedAt), timestamp(finishedAt),
                id);
    }

    public void fail(long id, String error) {
        jdbc.update("""
                UPDATE simulation.simulation_run SET status = 'FAILED', error = ?, finished_at = now()
                WHERE id = ?""", error, id);
    }

    public void saveForecasts(long runId, List<Forecast> forecasts) {
        jdbc.batchUpdate("""
                INSERT INTO simulation.customer_forecast (run_id, customer_id, forecast) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (run_id, customer_id) DO UPDATE SET forecast = EXCLUDED.forecast""", forecasts, 1000,
                (ps, f) -> {
                    ps.setLong(1, runId);
                    ps.setLong(2, f.customerId());
                    ps.setString(3, f.forecastJson());
                });
    }

    /** Forecast of a customer in the given run, or in the latest succeeded run that has one. */
    public Optional<Forecast> forecast(long customerId, Long runId) {
        String sql = """
                SELECT f.customer_id, f.run_id, s.code, s.name, r.start_date, f.forecast::text
                FROM simulation.customer_forecast f
                JOIN simulation.simulation_run r ON r.id = f.run_id
                JOIN simulation.scenario s ON s.id = r.scenario_id
                WHERE f.customer_id = ? AND r.status = 'SUCCEEDED'
                """ + (runId == null ? "" : " AND f.run_id = ?") + " ORDER BY f.run_id DESC LIMIT 1";
        RowMapper<Forecast> mapper = (rs, i) -> new Forecast(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), toLocalDate(rs.getDate(5)), rs.getString(6));
        List<Forecast> rows = runId == null ? jdbc.query(sql, mapper, customerId)
                : jdbc.query(sql, mapper, customerId, runId);
        return rows.stream().findFirst();
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static LocalDate toLocalDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}

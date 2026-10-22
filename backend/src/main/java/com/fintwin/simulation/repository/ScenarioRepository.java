package com.fintwin.simulation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Scenarios ({@code simulation.scenario}); parameters are kept as the ScenarioParameters JSON. */
@Repository
public class ScenarioRepository {

    public record Scenario(long id, String code, String name, String description, String parametersJson,
                           boolean preset, Instant createdAt, Instant updatedAt) {
    }

    private static final String COLUMNS =
            "id, code, name, description, parameters::text, preset, created_at, updated_at";
    private static final RowMapper<Scenario> MAPPER = (rs, i) -> new Scenario(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getTimestamp(7).toInstant(),
            rs.getTimestamp(8).toInstant());

    private final JdbcTemplate jdbc;

    public ScenarioRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Scenario> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM simulation.scenario ORDER BY preset DESC, id", MAPPER);
    }

    public Optional<Scenario> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM simulation.scenario WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Scenario> findByCode(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM simulation.scenario WHERE code = ?", MAPPER, code)
                .stream().findFirst();
    }

    public long insert(String code, String name, String description, String parametersJson) {
        Long id = jdbc.queryForObject("""
                INSERT INTO simulation.scenario (code, name, description, parameters)
                VALUES (?, ?, ?, ?::jsonb) RETURNING id""", Long.class, code, name, description, parametersJson);
        return id == null ? 0 : id;
    }

    public void update(long id, String code, String name, String description, String parametersJson) {
        jdbc.update("""
                UPDATE simulation.scenario SET code = ?, name = ?, description = ?, parameters = ?::jsonb,
                       updated_at = now()
                WHERE id = ?""", code, name, description, parametersJson, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM simulation.scenario WHERE id = ?", id);
    }

    public boolean hasRuns(long id) {
        Boolean used = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM simulation.simulation_run
                               WHERE scenario_id = ? OR baseline_scenario_id = ?)""", Boolean.class, id, id);
        return Boolean.TRUE.equals(used);
    }
}

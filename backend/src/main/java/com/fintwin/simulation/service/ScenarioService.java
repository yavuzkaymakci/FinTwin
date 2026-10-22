package com.fintwin.simulation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.simulation.dto.ScenarioParameters;
import com.fintwin.simulation.dto.ScenarioRequest;
import com.fintwin.simulation.dto.ScenarioView;
import com.fintwin.simulation.repository.ScenarioRepository;
import com.fintwin.simulation.repository.ScenarioRepository.Scenario;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Scenario CRUD; presets (seeded by V9) are read-only. */
@Service
public class ScenarioService {

    public static final String BASE = "BASE";

    private final ScenarioRepository scenarios;
    private final ObjectMapper json;

    public ScenarioService(ScenarioRepository scenarios, ObjectMapper json) {
        this.scenarios = scenarios;
        this.json = json;
    }

    public List<ScenarioView> list() {
        return scenarios.findAll().stream().map(this::view).toList();
    }

    public ScenarioView get(long id) {
        return view(find(id));
    }

    public ScenarioView create(ScenarioRequest request) {
        try {
            long id = scenarios.insert(request.code(), request.name(), description(request), write(request.parameters()));
            return get(id);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("A scenario with code " + request.code() + " already exists");
        }
    }

    public ScenarioView update(long id, ScenarioRequest request) {
        requireEditable(find(id));
        try {
            scenarios.update(id, request.code(), request.name(), description(request), write(request.parameters()));
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("A scenario with code " + request.code() + " already exists");
        }
        return get(id);
    }

    public void delete(long id) {
        Scenario scenario = find(id);
        requireEditable(scenario);
        if (scenarios.hasRuns(id)) {
            throw new IllegalStateException("Scenario " + scenario.code() + " has simulation runs and is kept");
        }
        scenarios.delete(id);
    }

    Scenario find(long id) {
        return scenarios.findById(id).orElseThrow(() -> new SimulationNotFoundException("Unknown scenario " + id));
    }

    Optional<Scenario> findByCode(String code) {
        return scenarios.findByCode(code);
    }

    ScenarioParameters parameters(Scenario scenario) {
        try {
            return json.readValue(scenario.parametersJson(), ScenarioParameters.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid parameters of scenario " + scenario.code(), e);
        }
    }

    private ScenarioView view(Scenario s) {
        return new ScenarioView(s.id(), s.code(), s.name(), s.description(), parameters(s), s.preset(),
                s.createdAt(), s.updatedAt());
    }

    private static void requireEditable(Scenario scenario) {
        if (scenario.preset()) {
            throw new IllegalStateException("Preset scenario " + scenario.code() + " cannot be changed");
        }
    }

    private static String description(ScenarioRequest request) {
        return request.description() == null ? "" : request.description();
    }

    private String write(ScenarioParameters parameters) {
        try {
            return json.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

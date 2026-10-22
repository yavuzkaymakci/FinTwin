package com.fintwin.simulation.controller;

import com.fintwin.ml.MlServiceUnavailableException;
import com.fintwin.simulation.dto.CustomerForecastView;
import com.fintwin.simulation.dto.RunComparison;
import com.fintwin.simulation.dto.RunRequest;
import com.fintwin.simulation.dto.ScenarioRequest;
import com.fintwin.simulation.dto.ScenarioView;
import com.fintwin.simulation.dto.SimulationRunView;
import com.fintwin.simulation.service.ScenarioService;
import com.fintwin.simulation.service.SimulationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Scenarios, simulation runs and per-customer cash-flow forecasts. */
@RestController
public class SimulationController {

    private final ScenarioService scenarios;
    private final SimulationService simulations;

    public SimulationController(ScenarioService scenarios, SimulationService simulations) {
        this.scenarios = scenarios;
        this.simulations = simulations;
    }

    @GetMapping("/api/scenarios")
    public List<ScenarioView> scenarios() {
        return scenarios.list();
    }

    @GetMapping("/api/scenarios/{id}")
    public ScenarioView scenario(@PathVariable long id) {
        return scenarios.get(id);
    }

    @PostMapping("/api/scenarios")
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioView create(@Valid @RequestBody ScenarioRequest request) {
        return scenarios.create(request);
    }

    @PutMapping("/api/scenarios/{id}")
    public ScenarioView update(@PathVariable long id, @Valid @RequestBody ScenarioRequest request) {
        return scenarios.update(id, request);
    }

    @DeleteMapping("/api/scenarios/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        scenarios.delete(id);
    }

    /** Starts a run in the background; poll {@code GET /api/simulation-runs/{id}} for status and results. */
    @PostMapping("/api/scenarios/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SimulationRunView run(@PathVariable long id, @Valid @RequestBody(required = false) RunRequest request) {
        return simulations.start(id, request);
    }

    /** Recent runs, newest first, without results ({@code scenarioId} filters one scenario). */
    @GetMapping("/api/simulation-runs")
    public List<SimulationRunView> runs(@RequestParam(required = false) Long scenarioId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return simulations.recent(scenarioId, limit);
    }

    @GetMapping("/api/simulation-runs/{id}")
    public SimulationRunView simulationRun(@PathVariable long id) {
        return simulations.view(id);
    }

    @GetMapping("/api/simulation-runs/compare")
    public RunComparison compare(@RequestParam List<Long> ids) {
        return simulations.compare(ids);
    }

    /** Fan chart data of a customer: from the given run, or from the latest succeeded run. */
    @GetMapping("/api/twins/{customerId}/forecast")
    public CustomerForecastView forecast(@PathVariable long customerId, @RequestParam(required = false) Long runId) {
        return simulations.forecast(customerId, runId);
    }

    @ExceptionHandler(MlServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> mlUnavailable(MlServiceUnavailableException e) {
        return Map.of("error", "ML_SERVICE_UNAVAILABLE", "detail", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}

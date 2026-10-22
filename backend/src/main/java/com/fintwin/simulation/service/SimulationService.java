package com.fintwin.simulation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.ml.MlServiceClient;
import com.fintwin.ml.MlServiceUnavailableException;
import com.fintwin.ml.MlSimulationApi;
import com.fintwin.simulation.domain.RunStatus;
import com.fintwin.simulation.dto.CustomerForecastView;
import com.fintwin.simulation.dto.RunComparison;
import com.fintwin.simulation.dto.RunRequest;
import com.fintwin.simulation.dto.ScenarioParameters;
import com.fintwin.simulation.dto.SimulationRunView;
import com.fintwin.simulation.repository.ScenarioRepository.Scenario;
import com.fintwin.simulation.repository.SimulationRunRepository;
import com.fintwin.simulation.repository.SimulationRunRepository.Forecast;
import com.fintwin.simulation.repository.SimulationRunRepository.Run;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Starts simulation runs on the ML service, follows them (polling) and stores their results durably. The ML service
 * keeps jobs in memory only, so results are copied into {@code simulation_run.result} and
 * {@code customer_forecast} as soon as a job succeeded.
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final ScenarioService scenarios;
    private final SimulationRunRepository runs;
    private final MlServiceClient client;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final int forecastPageSize;

    public SimulationService(ScenarioService scenarios, SimulationRunRepository runs, MlServiceClient client,
                             ObjectMapper json, TransactionTemplate transactions,
                             @Value("${fintwin.simulation.forecast-page-size:2000}") int forecastPageSize) {
        this.scenarios = scenarios;
        this.runs = runs;
        this.client = client;
        this.json = json;
        this.transactions = transactions;
        this.forecastPageSize = forecastPageSize;
    }

    /**
     * Creates a run and submits it to the ML service.
     *
     * @throws MlServiceUnavailableException if the ML service cannot be reached (the run is stored as FAILED)
     * @throws IllegalArgumentException      if the ML service rejects the request (e.g. invalid assumptions)
     */
    public SimulationRunView start(long scenarioId, RunRequest request) {
        RunRequest options = request == null ? RunRequest.defaults() : request;
        Scenario scenario = scenarios.find(scenarioId);
        Optional<Scenario> baseline = options.baselineScenarioId() != null
                ? Optional.of(scenarios.find(options.baselineScenarioId()))
                : scenarios.findByCode(ScenarioService.BASE).filter(b -> b.id() != scenario.id());
        JsonNode parameters = tree(scenarios.parameters(scenario));
        JsonNode baselineParameters = baseline.map(b -> tree(scenarios.parameters(b))).orElse(null);
        long runId = runs.insert(scenario.id(), baseline.map(Scenario::id).orElse(null), options.startDate(),
                parameters.toString(), baselineParameters == null ? null : baselineParameters.toString(),
                options.assumptions() == null ? null : options.assumptions().toString());
        var mlRequest = new MlSimulationApi.SimulationRequest(parameters, baselineParameters, options.startDate(),
                options.assumptions(), options.topCustomers() == null ? 20 : options.topCustomers(), true);
        try {
            MlSimulationApi.Job job = client.startSimulation(mlRequest);
            runs.markSubmitted(runId, job.jobId(), status(job.status()));
            log.info("Simulation run {} ({}) submitted as ML job {}", runId, scenario.code(), job.jobId());
        } catch (MlServiceUnavailableException e) {
            runs.fail(runId, e.getMessage());
            throw e;
        } catch (HttpClientErrorException e) {
            runs.fail(runId, "Rejected by the ML service: " + e.getResponseBodyAsString());
            throw new IllegalArgumentException("ML service rejected the simulation: " + e.getResponseBodyAsString());
        }
        return view(runId);
    }

    /** Polls the ML service for every active run; called periodically by {@link SimulationPoller}. */
    public void pollActiveRuns() {
        for (Run run : runs.findActive()) {
            try {
                poll(run);
            } catch (MlServiceUnavailableException e) {
                log.warn("ML service unavailable while polling simulation runs: {}", e.getMessage());
                return;  // try again next cycle; the circuit breaker limits the load meanwhile
            }
        }
    }

    void poll(Run run) {
        MlSimulationApi.Job job;
        try {
            job = client.simulation(run.mlJobId());
        } catch (HttpClientErrorException.NotFound e) {
            runs.fail(run.id(), "ML job " + run.mlJobId() + " is unknown to the ML service (restarted?)");
            return;
        }
        RunStatus status = status(job.status());
        Instant started = job.startedAt() == null ? null : job.startedAt().toInstant();
        switch (status) {
            case SUCCEEDED -> collect(run, job, started);
            case FAILED -> runs.fail(run.id(), job.error());
            default -> runs.updateProgress(run.id(), status, job.progress(), started);
        }
    }

    private void collect(Run run, MlSimulationApi.Job job, Instant started) {
        List<Forecast> forecasts = new ArrayList<>();
        int offset = 0;
        while (true) {
            MlSimulationApi.ForecastPage page = client.simulationForecasts(run.mlJobId(), offset, forecastPageSize);
            for (JsonNode item : page.items()) {
                forecasts.add(new Forecast(item.get("customerId").asLong(), run.id(), null, null, null,
                        item.toString()));
            }
            offset += page.items().size();
            if (page.items().isEmpty() || offset >= page.total()) {
                break;
            }
        }
        Instant finished = job.finishedAt() == null ? Instant.now() : job.finishedAt().toInstant();
        transactions.executeWithoutResult(tx -> {
            runs.saveForecasts(run.id(), forecasts);
            runs.succeed(run.id(), job.result().toString(), job.durationSeconds(), job.peakRssMb(), started, finished);
        });
        log.info("Simulation run {} succeeded: {} customer forecasts stored", run.id(), forecasts.size());
    }

    public SimulationRunView view(long runId) {
        return runs.findById(runId).map(this::view)
                .orElseThrow(() -> new SimulationNotFoundException("Unknown simulation run " + runId));
    }

    public RunComparison compare(List<Long> ids) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must list at least one run");
        }
        List<Run> found = runs.findByIds(ids);
        if (found.size() != ids.size()) {
            throw new SimulationNotFoundException("Unknown simulation run among " + ids);
        }
        List<RunComparison.Row> rows = new ArrayList<>();
        JsonNode first = null;
        for (Run run : found) {
            if (run.status() != RunStatus.SUCCEEDED) {
                throw new IllegalStateException("Simulation run " + run.id() + " is " + run.status());
            }
            JsonNode result = read(run.resultJson());
            JsonNode portfolio = result.path("portfolio");
            first = first == null ? portfolio : first;
            Map<String, Double> segments = new LinkedHashMap<>();
            for (JsonNode segment : result.path("segments")) {
                segments.put(segment.path("segment").asText(), segment.path("defaultRate").path("mean").asDouble());
            }
            rows.add(new RunComparison.Row(run.id(), run.scenarioCode(), run.scenarioName(), run.startDate() != null
                    ? run.startDate() : LocalDate.parse(result.path("startDate").asText()),
                    result.path("horizonMonths").asInt(), result.path("paths").asInt(),
                    stats(portfolio.path("defaultRate")), stats(portfolio.path("churnRate")),
                    portfolio.path("expectedLoss").asDouble(), portfolio.path("lossP99").asDouble(),
                    portfolio.path("expectedLossPctOfEad").asDouble(), segments,
                    portfolio.path("defaultRate").path("mean").asDouble()
                            - first.path("defaultRate").path("mean").asDouble(),
                    portfolio.path("expectedLoss").asDouble() - first.path("expectedLoss").asDouble()));
        }
        return new RunComparison(rows);
    }

    public CustomerForecastView forecast(long customerId, Long runId) {
        Forecast f = runs.forecast(customerId, runId).orElseThrow(() -> new SimulationNotFoundException(
                "No forecast for customer " + customerId + (runId == null ? "" : " in run " + runId)));
        return new CustomerForecastView(f.customerId(), f.runId(), f.scenarioCode(), f.scenarioName(), f.startDate(),
                read(f.forecastJson()));
    }

    private SimulationRunView view(Run r) {
        return new SimulationRunView(r.id(), r.scenarioId(), r.scenarioCode(), r.scenarioName(),
                r.baselineScenarioId(), r.startDate(), r.status().name(), r.progress(), r.requestedAt(),
                r.startedAt(), r.finishedAt(), r.error(), r.durationSeconds(), r.peakRssMb(),
                r.resultJson() == null ? null : read(r.resultJson()));
    }

    private static Map<String, Double> stats(JsonNode node) {
        Map<String, Double> out = new LinkedHashMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asDouble()));
        return out;
    }

    private static RunStatus status(String mlStatus) {
        return mlStatus == null ? RunStatus.QUEUED : RunStatus.valueOf(mlStatus);
    }

    private JsonNode tree(ScenarioParameters parameters) {
        return json.valueToTree(parameters);
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in simulation data", e);
        }
    }
}

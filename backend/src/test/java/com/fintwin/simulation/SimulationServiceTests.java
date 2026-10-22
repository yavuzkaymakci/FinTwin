package com.fintwin.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.TestcontainersConfiguration;
import com.fintwin.ml.MlServiceClient;
import com.fintwin.ml.MlServiceUnavailableException;
import com.fintwin.ml.MlSimulationApi;
import com.fintwin.simulation.dto.RunComparison;
import com.fintwin.simulation.dto.RunRequest;
import com.fintwin.simulation.dto.ScenarioParameters;
import com.fintwin.simulation.dto.ScenarioRequest;
import com.fintwin.simulation.dto.ScenarioView;
import com.fintwin.simulation.dto.SimulationRunView;
import com.fintwin.simulation.service.ScenarioService;
import com.fintwin.simulation.service.SimulationNotFoundException;
import com.fintwin.simulation.service.SimulationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.verify;

/** Scenario CRUD and the run lifecycle against the real schema; the ML service is mocked at the client. */
@SpringBootTest(properties = "fintwin.simulation.forecast-page-size=2")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SimulationServiceTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ScenarioService scenarios;

    @Autowired
    private SimulationService simulations;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MlServiceClient client;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM simulation.simulation_run");
        jdbc.update("DELETE FROM simulation.scenario WHERE NOT preset");
    }

    private static MlSimulationApi.Job job(String status, double progress, JsonNode result) {
        OffsetDateTime now = OffsetDateTime.now();
        return new MlSimulationApi.Job("job-1", status, progress, now, now, "SUCCEEDED".equals(status) ? now : null,
                "FAILED".equals(status) ? "ValueError: boom" : null, result == null ? null : 12.5,
                result == null ? null : 640.0, result);
    }

    private static JsonNode result(double defaultRate, double expectedLoss) throws Exception {
        return JSON.readTree("""
                {"startDate": "2026-10-01", "horizonMonths": 12, "paths": 200,
                 "portfolio": {"defaultRate": {"mean": %s, "p5": 0.02, "p50": 0.03, "p95": 0.05},
                               "churnRate": {"mean": 0.05, "p5": 0.04, "p50": 0.05, "p95": 0.06},
                               "expectedLoss": %s, "lossP99": 9000000, "expectedLossPctOfEad": 2.1},
                 "segments": [{"segment": "STUDENT", "defaultRate": {"mean": 0.15}}]}"""
                .formatted(defaultRate, expectedLoss));
    }

    private static MlSimulationApi.ForecastPage page(int offset, long... customers) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        for (long id : customers) {
            items.add(JSON.readTree("{\"customerId\": %d, \"months\": [{\"month\": \"2026-10\", \"balance\": "
                    .formatted(id) + "{\"p10\": 1, \"p50\": 2, \"p90\": 3}}]}"));
        }
        return new MlSimulationApi.ForecastPage("job-1", 3, offset, items);
    }

    private long scenarioId(String code) {
        return scenarios.list().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow().id();
    }

    @Test
    void presetsAreSeededAndReadOnly() {
        assertThat(scenarios.list()).extracting(ScenarioView::code)
                .contains("BASE", "FX_SHOCK", "RATE_SHOCK", "STAGFLATION", "RECOVERY");
        ScenarioView fx = scenarios.get(scenarioId("FX_SHOCK"));
        assertThat(fx.parameters().usdTryChangePct()).isEqualTo(30.0);
        assertThat(fx.parameters().monthlyInflationPct()).first().isEqualTo(4.5);
        assertThatThrownBy(() -> scenarios.delete(fx.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsUpdatesAndDeletesScenarios() {
        var parameters = new ScenarioParameters(6, 50.0, 1000, List.of(5.0), 70.0, 12, null,
                ScenarioParameters.ShockProfile.IMMEDIATE, null, 50, 1L);
        ScenarioView created = scenarios.create(new ScenarioRequest("SEVERE_FX", "Ağır kur şoku", null, parameters));
        assertThat(created.preset()).isFalse();
        assertThat(created.parameters()).isEqualTo(parameters);
        assertThatThrownBy(() -> scenarios.create(new ScenarioRequest("SEVERE_FX", "dup", null, parameters)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        ScenarioView renamed = scenarios.update(created.id(), new ScenarioRequest("SEVERE_FX", "Renamed", "d",
                parameters));
        assertThat(renamed.name()).isEqualTo("Renamed");
        scenarios.delete(created.id());
        assertThatThrownBy(() -> scenarios.get(created.id())).isInstanceOf(SimulationNotFoundException.class);
    }

    @Test
    void runsAreSubmittedAgainstTheBaseScenarioPolledAndStored() throws Exception {
        long fx = scenarioId("FX_SHOCK");
        given(client.startSimulation(any())).willReturn(job("QUEUED", 0, null));
        SimulationRunView started = simulations.start(fx, new RunRequest(LocalDate.of(2025, 9, 1), null, null, 5));
        assertThat(started.status()).isEqualTo("QUEUED");
        assertThat(started.baselineScenarioId()).isEqualTo(scenarioId("BASE"));

        ArgumentCaptor<MlSimulationApi.SimulationRequest> sent = ArgumentCaptor.forClass(
                MlSimulationApi.SimulationRequest.class);
        verify(client).startSimulation(sent.capture());
        assertThat(sent.getValue().scenario().get("usdTryChangePct").asDouble()).isEqualTo(30.0);
        assertThat(sent.getValue().baseline().get("policyRateChangeBp").asInt()).isEqualTo(-300);
        assertThat(sent.getValue().startDate()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(sent.getValue().topCustomers()).isEqualTo(5);

        given(client.simulation("job-1")).willReturn(job("RUNNING", 40, null));
        simulations.pollActiveRuns();
        assertThat(simulations.view(started.id()).progress()).isEqualTo(40.0);
        assertThat(simulations.view(started.id()).status()).isEqualTo("RUNNING");

        given(client.simulation("job-1")).willReturn(job("SUCCEEDED", 100, result(0.04, 7_500_000)));
        given(client.simulationForecasts("job-1", 0, 2)).willReturn(page(0, 7, 8));
        given(client.simulationForecasts("job-1", 2, 2)).willReturn(page(2, 9));
        simulations.pollActiveRuns();
        SimulationRunView done = simulations.view(started.id());
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.result().path("portfolio").path("expectedLoss").asDouble()).isEqualTo(7_500_000);
        assertThat(done.durationSeconds()).isEqualTo(12.5);
        assertThat(done.peakRssMb()).isEqualTo(640.0);
        assertThat(simulations.forecast(9, null).forecast().path("months").get(0).path("balance").path("p50")
                .asDouble()).isEqualTo(2);
        assertThat(simulations.forecast(8, started.id()).runId()).isEqualTo(started.id());
        assertThatThrownBy(() -> simulations.forecast(10, null)).isInstanceOf(SimulationNotFoundException.class);
    }

    @Test
    void comparesFinishedRuns() throws Exception {
        given(client.startSimulation(any())).willReturn(job("QUEUED", 0, null));
        long base = simulations.start(scenarioId("BASE"), null).id();
        long fx = simulations.start(scenarioId("FX_SHOCK"), null).id();
        given(client.simulation("job-1")).willReturn(job("SUCCEEDED", 100, result(0.03, 5_000_000)));
        given(client.simulationForecasts(eq("job-1"), anyInt(), anyInt())).willReturn(page(0));
        simulations.pollActiveRuns();
        jdbc.update("UPDATE simulation.simulation_run SET result = jsonb_set(result, '{portfolio,expectedLoss}', "
                + "'7000000') WHERE id = ?", fx);
        assertThat(simulations.recent(null, 10)).extracting(SimulationRunView::id).containsExactly(fx, base);
        assertThat(simulations.recent(scenarioId("BASE"), 10)).extracting(SimulationRunView::id).containsExactly(base);
        assertThat(simulations.recent(null, 10).getFirst().result()).isNull();  // listing omits the result JSON
        RunComparison comparison = simulations.compare(List.of(base, fx));
        assertThat(comparison.runs()).extracting(RunComparison.Row::scenarioCode).containsExactly("BASE", "FX_SHOCK");
        assertThat(comparison.runs().get(1).expectedLossDeltaVsFirst()).isEqualTo(2_000_000);
        assertThat(comparison.runs().get(0).segmentDefaultRate()).containsEntry("STUDENT", 0.15);
        assertThat(simulations.view(base).baselineScenarioId()).isNull();  // BASE has no baseline of its own
    }

    @Test
    void failuresAreRecordedOnTheRun() {
        long fx = scenarioId("FX_SHOCK");
        given(client.startSimulation(any())).willThrow(new MlServiceUnavailableException("connection refused", null));
        assertThatThrownBy(() -> simulations.start(fx, null)).isInstanceOf(MlServiceUnavailableException.class);
        Long failed = jdbc.queryForObject("SELECT COUNT(*) FROM simulation.simulation_run WHERE status = 'FAILED'",
                Long.class);
        assertThat(failed).isEqualTo(1);

        willReturn(job("QUEUED", 0, null)).given(client).startSimulation(any());
        long lost = simulations.start(fx, null).id();
        given(client.simulation("job-1")).willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf",
                null, null, null));
        simulations.pollActiveRuns();
        assertThat(simulations.view(lost).status()).isEqualTo("FAILED");
        assertThat(simulations.view(lost).error()).contains("unknown to the ML service");

        assertThatThrownBy(() -> simulations.compare(List.of(lost))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> simulations.view(999_999)).isInstanceOf(SimulationNotFoundException.class);
    }
}

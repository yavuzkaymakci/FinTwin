package com.fintwin.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.config.SecurityConfig;
import com.fintwin.ml.MlServiceUnavailableException;
import com.fintwin.simulation.controller.SimulationController;
import com.fintwin.simulation.dto.CustomerForecastView;
import com.fintwin.simulation.dto.ScenarioParameters;
import com.fintwin.simulation.dto.ScenarioView;
import com.fintwin.simulation.dto.SimulationRunView;
import com.fintwin.simulation.service.ScenarioService;
import com.fintwin.simulation.service.SimulationNotFoundException;
import com.fintwin.simulation.service.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SimulationController.class)
@Import(SecurityConfig.class)
class SimulationControllerTests {

    private static final ScenarioParameters FX = new ScenarioParameters(12, 30.0, 0, List.of(4.5, 2.0), null, null,
            null, null, null, null, null);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ScenarioService scenarios;

    @MockitoBean
    private SimulationService simulations;

    private static SimulationRunView run(String status) {
        return new SimulationRunView(3, 2, "FX_SHOCK", "Kur şoku", 1L, null, status, 0, Instant.now(), null, null,
                null, null, null, null);
    }

    @Test
    void listsScenarios() throws Exception {
        given(scenarios.list()).willReturn(List.of(new ScenarioView(2, "FX_SHOCK", "Kur şoku", "", FX, true,
                Instant.now(), Instant.now())));
        mvc.perform(get("/api/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("FX_SHOCK"))
                .andExpect(jsonPath("$[0].parameters.usdTryChangePct").value(30.0))
                .andExpect(jsonPath("$[0].parameters.monteCarloPaths").value(200));
    }

    @Test
    void validatesNewScenarios() throws Exception {
        String invalid = """
                {"code": "MY_SHOCK", "name": "x", "parameters": {"monthlyInflationPct": [2], "horizonMonths": 99}}""";
        mvc.perform(post("/api/scenarios").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(scenarios);

        given(scenarios.create(any())).willReturn(new ScenarioView(9, "MY_SHOCK", "x", "", FX, false,
                Instant.now(), Instant.now()));
        String valid = new ObjectMapper().writeValueAsString(java.util.Map.of("code", "MY_SHOCK", "name", "x",
                "parameters", FX));
        mvc.perform(post("/api/scenarios").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(9));
    }

    @Test
    void startsARunWithOrWithoutOptions() throws Exception {
        given(simulations.start(eq(2L), isNull())).willReturn(run("QUEUED"));
        mvc.perform(post("/api/scenarios/2/run")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        given(simulations.start(eq(2L), any())).willReturn(run("QUEUED"));
        mvc.perform(post("/api/scenarios/2/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\": \"2025-09-01\", \"topCustomers\": 5}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/scenarios/2/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"topCustomers\": 5000}")).andExpect(status().isBadRequest());
    }

    @Test
    void mlServiceOutageIs503AndConflictsAre409() throws Exception {
        given(simulations.start(anyLong(), any())).willThrow(new MlServiceUnavailableException("down", null));
        mvc.perform(post("/api/scenarios/2/run")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ML_SERVICE_UNAVAILABLE"));
        given(simulations.compare(List.of(3L, 4L))).willThrow(new IllegalStateException("run 4 is RUNNING"));
        mvc.perform(get("/api/simulation-runs/compare").param("ids", "3,4")).andExpect(status().isConflict());
    }

    @Test
    void forecastOfACustomer() throws Exception {
        given(simulations.forecast(7, null)).willReturn(new CustomerForecastView(7, 3, "BASE", "Baz senaryo",
                LocalDate.of(2026, 10, 1), new ObjectMapper().readTree("{\"months\": [{\"month\": \"2026-10\"}]}")));
        mvc.perform(get("/api/twins/7/forecast")).andExpect(status().isOk())
                .andExpect(jsonPath("$.forecast.months[0].month").value("2026-10"));
        given(simulations.forecast(8, 3L)).willThrow(new SimulationNotFoundException("No forecast"));
        mvc.perform(get("/api/twins/8/forecast").param("runId", "3")).andExpect(status().isNotFound());
    }
}

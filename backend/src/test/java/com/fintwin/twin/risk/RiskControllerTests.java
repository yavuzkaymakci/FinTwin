package com.fintwin.twin.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.config.SecurityConfig;
import com.fintwin.twin.risk.client.MlServiceUnavailableException;
import com.fintwin.twin.risk.controller.RiskController;
import com.fintwin.twin.risk.dto.RiskScoreView;
import com.fintwin.twin.risk.dto.ScoringStatus;
import com.fintwin.twin.risk.service.RiskScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RiskController.class)
@Import(SecurityConfig.class)
class RiskControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RiskScoringService scoring;

    private static RiskScoreView view() throws Exception {
        return new RiskScoreView(7, 0.031, 0.02, 6, new ObjectMapper().readTree("""
                {"pd": [{"code": "UTILIZATION_RATE_HIGH", "textTr": "Kredi kartı limit kullanımı %92"}], "churn": []}"""),
                "pd-1", "churn-1", Instant.parse("2026-10-22T07:00:00Z"), Instant.parse("2026-09-30T18:00:00Z"));
    }

    @Test
    void returnsTheLatestScore() throws Exception {
        given(scoring.latest(7)).willReturn(Optional.of(view()));
        mvc.perform(get("/api/twins/7/risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskGrade").value(6))
                .andExpect(jsonPath("$.reasons.pd[0].code").value("UTILIZATION_RATE_HIGH"));
    }

    @Test
    void unknownOrUnscoredCustomerIs404() throws Exception {
        given(scoring.latest(8)).willReturn(Optional.empty());
        mvc.perform(get("/api/twins/8/risk")).andExpect(status().isNotFound());
    }

    @Test
    void mlServiceOutageIs503() throws Exception {
        given(scoring.scoreNow(7)).willThrow(new MlServiceUnavailableException("ML service circuit breaker is open",
                null));
        mvc.perform(get("/api/twins/7/risk").param("refresh", "true"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ML_SERVICE_UNAVAILABLE"));
    }

    @Test
    void startsPortfolioScoringOnce() throws Exception {
        given(scoring.startPortfolioScoring()).willReturn(new ScoringStatus("RUNNING", Instant.now(), 5000, 0,
                Instant.now(), null, 0.0, null, "CLOSED"));
        mvc.perform(post("/api/admin/risk/score-portfolio")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        given(scoring.startPortfolioScoring()).willThrow(new IllegalStateException("already running"));
        mvc.perform(post("/api/admin/risk/score-portfolio")).andExpect(status().isConflict());
    }
}

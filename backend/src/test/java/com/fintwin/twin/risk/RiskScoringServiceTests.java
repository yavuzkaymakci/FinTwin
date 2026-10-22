package com.fintwin.twin.risk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fintwin.TestcontainersConfiguration;
import com.fintwin.ml.MlRiskApi;
import com.fintwin.ml.MlServiceClient;
import com.fintwin.ml.MlServiceUnavailableException;
import com.fintwin.twin.risk.dto.RiskScoreView;
import com.fintwin.twin.risk.service.RiskScoringService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Portfolio scoring against the real twin schema; the ML service is mocked at the client boundary. */
@SpringBootTest(properties = "fintwin.ml.batch-size=2")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RiskScoringServiceTests {

    private static final long FIRST = 990_001;
    private static final int TWINS = 5;

    @Autowired
    private RiskScoringService scoring;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MlServiceClient client;

    @BeforeEach
    void insertTwins() {
        for (long id = FIRST; id < FIRST + TWINS; id++) {
            jdbc.update("""
                    INSERT INTO twin.customer_twin_state (customer_id, version, last_seq, last_event_at, state, features,
                        checking_balance, deposit_balance, card_debt, total_debt, spend_30d, spend_90d,
                        monthly_obligations, late_payment_count)
                    VALUES (?, 1, 10, now(), '{"segment": "STUDENT"}', ?::jsonb, 0, 0, 0, 0, 0, 0, 0, 0)""",
                    id, "{\"utilizationRate\": " + (id - FIRST) / 10.0 + "}");
        }
        given(client.circuitState()).willReturn(CircuitBreaker.State.CLOSED);
        given(client.scoreBatch(anyList())).willAnswer(inv -> {
            List<MlRiskApi.ScoreRequest> items = inv.getArgument(0);
            return items.stream().map(r -> new MlRiskApi.Score(r.customerId(),
                    0.01 + r.features().path("utilizationRate").asDouble() / 10, 0.02, 5, true,
                    List.of(JsonNodeFactory.instance.objectNode().put("code", "UTILIZATION_RATE_HIGH")),
                    List.of(), "pd-1", "churn-1")).toList();
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM twin.customer_risk_score WHERE customer_id >= ?", FIRST);
        jdbc.update("DELETE FROM twin.customer_twin_state WHERE customer_id >= ?", FIRST);
    }

    @Test
    void scoresThePortfolioInBatchesAndStoresEveryScore() {
        long existing = jdbc.queryForObject("SELECT COUNT(*) FROM twin.customer_twin_state", Long.class);
        scoring.startPortfolioScoring();
        await().atMost(Duration.ofSeconds(30)).until(() -> !"RUNNING".equals(scoring.status().state()));

        assertThat(scoring.status().state()).isEqualTo("SUCCEEDED");
        assertThat(scoring.status().scored()).isEqualTo(existing);
        verify(client, times((int) Math.ceil(existing / 2.0))).scoreBatch(anyList());
        RiskScoreView latest = scoring.latest(FIRST + 3).orElseThrow();
        assertThat(latest.pd()).isCloseTo(0.04, within(1e-12));
        assertThat(latest.riskGrade()).isEqualTo(5);
        assertThat(latest.reasons().get("pd").get(0).get("code").asText()).isEqualTo("UTILIZATION_RATE_HIGH");
        assertThat(latest.pdModelVersion()).isEqualTo("pd-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT scored_at) FROM twin.customer_risk_score "
                + "WHERE customer_id >= ?", Long.class, FIRST)).isEqualTo(1);
    }

    @Test
    void aFailingMlServiceFailsTheRunWithoutCrashing() {
        given(client.scoreBatch(anyList())).willThrow(new MlServiceUnavailableException("ML service down", null));
        scoring.startPortfolioScoring();
        await().atMost(Duration.ofSeconds(30)).until(() -> !"RUNNING".equals(scoring.status().state()));

        assertThat(scoring.status().state()).isEqualTo("FAILED");
        assertThat(scoring.status().error()).contains("ML service down");
        assertThatThrownBy(() -> scoring.scoreNow(FIRST)).isInstanceOf(MlServiceUnavailableException.class);
    }

    @Test
    void scoresOneCustomerOnDemand() {
        RiskScoreView now = scoring.scoreNow(FIRST + 1).orElseThrow();
        assertThat(now.pd()).isCloseTo(0.02, within(1e-12));
        assertThat(scoring.latest(FIRST + 1)).contains(now);
        assertThat(scoring.scoreNow(123_456_789)).isEmpty();
    }
}

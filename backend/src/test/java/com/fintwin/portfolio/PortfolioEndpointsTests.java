package com.fintwin.portfolio;

import com.fintwin.TestcontainersConfiguration;
import com.fintwin.ml.MlRiskApi;
import com.fintwin.ml.MlServiceClient;
import com.fintwin.ml.MlServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer list, portfolio summary and system status on a small fixture portfolio (real schema). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PortfolioEndpointsTests {

    private static final long A = 990_201;
    private static final long B = 990_202;
    private static final long C = 990_203;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MlServiceClient ml;

    @BeforeEach
    void fixture() {
        customer(A, "STUDENT", "ANKARA", 0.25, 0.04, 9, 0.95, 0.3, 40_000);
        customer(B, "WHITE_COLLAR", "ISTANBUL", 0.004, 0.02, 3, 0.20, 6.0, 10_000);
        customer(C, "WHITE_COLLAR", "IZMIR", 0.02, 0.06, 6, 0.50, 2.0, 0);
        // An older score of A must be ignored in favour of the latest one.
        jdbc.update("""
                INSERT INTO twin.customer_risk_score (customer_id, scored_at, features_as_of, pd, churn, risk_grade,
                    reasons, pd_model_version, churn_model_version)
                VALUES (?, now() - interval '1 day', now(), 0.001, 0.01, 1, '{}', 'old', 'old')""", A);
    }

    private void customer(long id, String segment, String city, double pd, double churn, int grade,
                          double utilization, double buffer, int debt) {
        jdbc.update("""
                INSERT INTO customer (id, age, city, occupation_group, segment, monthly_net_income, customer_since,
                                      status) VALUES (?, 30, ?, 'OFFICE_WORKER', ?, 40000, '2020-01-01', 'ACTIVE')""",
                id, city, segment);
        jdbc.update("""
                INSERT INTO twin.customer_twin_state (customer_id, version, last_seq, last_event_at, state, features,
                    checking_balance, deposit_balance, card_debt, total_debt, utilization_rate, spend_30d, spend_90d,
                    monthly_obligations, late_payment_count, liquidity_buffer_months)
                VALUES (?, 1, 10, now(), '{}', '{}', 1000, 500, ?, ?, ?, 0, 0, 0, 0, ?)""",
                id, debt, debt, utilization, buffer);
        jdbc.update("""
                INSERT INTO twin.customer_risk_score (customer_id, scored_at, features_as_of, pd, churn, risk_grade,
                    reasons, pd_model_version, churn_model_version)
                VALUES (?, now(), now(), ?, ?, ?, '{}', 'pd-1', 'churn-1')""", id, pd, churn, grade);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM twin.customer_risk_score WHERE customer_id >= ?", A);
        jdbc.update("DELETE FROM twin.customer_twin_state WHERE customer_id >= ?", A);
        jdbc.update("DELETE FROM customer WHERE id >= ?", A);
    }

    @Test
    void listsCustomersWithTheirLatestScoreFilteredSortedAndPaged() throws Exception {
        mvc.perform(get("/api/customers").param("sort", "pd").param("direction", "desc").param("minGrade", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(A))
                .andExpect(jsonPath("$.content[0].pd").value(0.25))
                .andExpect(jsonPath("$.content[0].riskGrade").value(9))
                .andExpect(jsonPath("$.content[0].liquidBalance").value(1500.0))
                .andExpect(jsonPath("$.content[1].id").value(C));
        mvc.perform(get("/api/customers").param("segment", "WHITE_COLLAR").param("minGrade", "5"))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(C));
        mvc.perform(get("/api/customers").param("search", "istan")).andExpect(jsonPath("$.content[0].id").value(B));
        mvc.perform(get("/api/customers").param("search", String.valueOf(C)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/customers").param("sort", "drop table")).andExpect(status().isBadRequest());
    }

    @Test
    void summarisesThePortfolio() throws Exception {
        // Fixture customers are added to whatever the shared test database holds, so check them via the segments
        // and the top list (other test classes clean up their scored customers).
        mvc.perform(get("/api/portfolio/summary").param("top", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoredCustomers").value(3))
                .andExpect(jsonPath("$.highRiskCustomers").value(1))
                .andExpect(jsonPath("$.customersByGrade.9").value(1))
                .andExpect(jsonPath("$.customersByGrade.1").value(0))
                .andExpect(jsonPath("$.lgd").value(0.70))
                // 0.25 * 0.7 * 40,000 + 0.004 * 0.7 * 10,000 + 0 = 7,028
                .andExpect(jsonPath("$.expectedLoss").value(7028.0))
                .andExpect(jsonPath("$.topRiskCustomers[0].customerId").value(A))
                .andExpect(jsonPath("$.topRiskCustomers.length()").value(2));
        mvc.perform(get("/api/portfolio/segments"))
                .andExpect(jsonPath("$[?(@.segment == 'WHITE_COLLAR')].customers").value(2))
                .andExpect(jsonPath("$[?(@.segment == 'STUDENT')].highRiskCustomers").value(1));
    }

    @Test
    void reportsTheMlServiceAndAiProvider() throws Exception {
        given(ml.circuitState()).willReturn(CircuitBreaker.State.CLOSED);
        given(ml.activeModels()).willReturn(new MlRiskApi.ActiveModels(List.of(new MlRiskApi.ModelInfo("pd", "v1",
                "2026-10-22T06:52:28Z", new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"testAuc\": 0.972}")))));
        mvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ml.reachable").value(true))
                .andExpect(jsonPath("$.ml.models[0].summary.testAuc").value(0.972))
                .andExpect(jsonPath("$.ai.provider").value("none"))
                .andExpect(jsonPath("$.ai.configured").value(false))
                .andExpect(jsonPath("$.replay.mode").exists());
    }

    @Test
    void anUnreachableMlServiceIsReportedNotThrown() throws Exception {
        given(ml.circuitState()).willReturn(CircuitBreaker.State.OPEN);
        given(ml.activeModels()).willThrow(new MlServiceUnavailableException("circuit breaker is open", null));
        // The breaker state is read on every call, even while the model list is served from the 10 s cache.
        mvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ml.circuitBreaker").value("OPEN"));
    }
}

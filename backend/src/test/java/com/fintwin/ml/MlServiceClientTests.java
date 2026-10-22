package com.fintwin.ml;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MlServiceClientTests {

    private static final String SCORE_URL = "http://ml/v1/risk/score/batch";
    private static final String RESPONSE = """
            {"scores": [{"customerId": 7, "pd": 0.031, "churn": 0.02, "riskGrade": 6, "pdApplicable": true,
              "pdReasons": [{"code": "UTILIZATION_RATE_HIGH", "feature": "utilization_rate", "value": 0.92,
                             "portfolioMean": 0.31, "impact": 0.8,
                             "text": "Credit card limit utilisation 92%, far above the portfolio average (31%)",
                             "textTr": "Kredi kartı limit kullanımı %92, portföy ortalamasının (%31) çok üstünde"}],
              "churnReasons": [], "pdModelVersion": "pd-1", "churnModelVersion": "churn-1"}]}""";

    private MockRestServiceServer server;
    private MlServiceClient client;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml");
        server = MockRestServiceServer.bindTo(builder).build();
        Retry retry = Retry.of("ml", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class).build());
        circuitBreaker = CircuitBreaker.of("ml", CircuitBreakerConfig.custom().slidingWindowSize(4)
                .minimumNumberOfCalls(4).failureRateThreshold(50).waitDurationInOpenState(Duration.ofMinutes(1))
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class).build());
        client = new MlServiceClient(builder.build(), retry, circuitBreaker);
    }

    private static List<MlRiskApi.ScoreRequest> request() {
        return List.of(new MlRiskApi.ScoreRequest(7, "STUDENT",
                JsonNodeFactory.instance.objectNode().put("utilizationRate", 0.92)));
    }

    @Test
    void scoresABatch() {
        server.expect(once(), requestTo(SCORE_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"items\":[{\"customerId\":7,\"segment\":\"STUDENT\","
                        + "\"features\":{\"utilizationRate\":0.92}}]}"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        MlRiskApi.Score score = client.scoreBatch(request()).getFirst();

        assertThat(score.pd()).isEqualTo(0.031);
        assertThat(score.riskGrade()).isEqualTo(6);
        assertThat(score.pdReasons().getFirst().get("textTr").asText()).startsWith("Kredi kartı limit kullanımı");
        server.verify();
    }

    @Test
    void retriesTransientFailures() {
        server.expect(once(), requestTo(SCORE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(SCORE_URL)).andRespond(withException(new IOException("connection reset")));
        server.expect(once(), requestTo(SCORE_URL)).andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        assertThat(client.scoreBatch(request())).hasSize(1);
        server.verify();
    }

    @Test
    void reportsUnavailabilityAfterRetriesAreExhausted() {
        server.expect(times(3), requestTo(SCORE_URL)).andRespond(withException(new IOException("refused")));

        assertThatThrownBy(() -> client.scoreBatch(request()))
                .isInstanceOf(MlServiceUnavailableException.class).hasMessageContaining("refused");
        server.verify();
    }

    @Test
    void opensTheCircuitAndFailsFast() {
        server.expect(times(4), requestTo(SCORE_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.scoreBatch(request())).isInstanceOf(MlServiceUnavailableException.class);
        assertThatThrownBy(() -> client.scoreBatch(request())).isInstanceOf(MlServiceUnavailableException.class);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // No further HTTP request: the open circuit rejects the call immediately.
        assertThatThrownBy(() -> client.scoreBatch(request()))
                .isInstanceOf(MlServiceUnavailableException.class).hasMessageContaining("circuit breaker is open");
        server.verify();
    }

    @Test
    void clientErrorsAreNotRetried() {
        server.expect(once(), requestTo(SCORE_URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> client.scoreBatch(request())).isInstanceOf(HttpClientErrorException.class);
        server.verify();
    }

    @Test
    void rejectsIncompleteBatches() {
        server.expect(once(), requestTo(SCORE_URL)).andRespond(withSuccess("{\"scores\": []}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.scoreBatch(request())).isInstanceOf(MlServiceUnavailableException.class);
    }

    @Test
    void submitsAndFollowsSimulations() {
        server.expect(once(), requestTo("http://ml/v1/simulations")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"scenario\": {\"horizonMonths\": 6}, \"topCustomers\": 5}"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"jobId": "j1", "status": "QUEUED", "progress": 0,
                         "submittedAt": "2026-10-22T08:00:00Z"}"""));
        server.expect(once(), requestTo("http://ml/v1/simulations/j1")).andRespond(withSuccess("""
                {"jobId": "j1", "status": "SUCCEEDED", "progress": 100, "submittedAt": "2026-10-22T08:00:00Z",
                 "durationSeconds": 3.5, "peakRssMb": 512.0, "result": {"portfolio": {"expectedLoss": 10}}}""",
                MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://ml/v1/simulations/j1/forecasts?offset=0&limit=100"))
                .andRespond(withSuccess("{\"jobId\": \"j1\", \"total\": 1, \"offset\": 0, \"items\": "
                        + "[{\"customerId\": 7}]}", MediaType.APPLICATION_JSON));

        var request = new MlSimulationApi.SimulationRequest(
                JsonNodeFactory.instance.objectNode().put("horizonMonths", 6), null, null, null, 5, null);
        assertThat(client.startSimulation(request).jobId()).isEqualTo("j1");
        MlSimulationApi.Job job = client.simulation("j1");
        assertThat(job.status()).isEqualTo("SUCCEEDED");
        assertThat(job.result().path("portfolio").path("expectedLoss").asInt()).isEqualTo(10);
        assertThat(client.simulationForecasts("j1", 0, 100).items()).hasSize(1);
        server.verify();
    }

    @Test
    void anUnknownSimulationIsNotRetried() {
        server.expect(once(), requestTo("http://ml/v1/simulations/gone")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.simulation("gone")).isInstanceOf(HttpClientErrorException.NotFound.class);
        server.verify();
    }
}

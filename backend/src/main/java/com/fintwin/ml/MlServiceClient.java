package com.fintwin.ml;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.function.Supplier;

/**
 * Client of the ML service. Every call runs as {@code Retry(CircuitBreaker(call))}: transient failures (connection
 * problems, 5xx) are retried with exponential back-off; repeated failures open the circuit, after which calls fail
 * fast. Any failure surfaces as {@link MlServiceUnavailableException}, so callers can degrade instead of crashing.
 * Client errors (4xx) are not retried and propagate unchanged — they indicate a bug in the request.
 */
public class MlServiceClient {

    public static final String RESILIENCE_NAME = "mlService";

    private final RestClient rest;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public MlServiceClient(RestClient rest, Retry retry, CircuitBreaker circuitBreaker) {
        this.rest = rest;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
    }

    public List<MlRiskApi.Score> scoreBatch(List<MlRiskApi.ScoreRequest> items) {
        MlRiskApi.BatchResponse response = call("score batch", () -> rest.post().uri("/v1/risk/score/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MlRiskApi.BatchRequest(items))
                .retrieve()
                .body(MlRiskApi.BatchResponse.class));
        if (response == null || response.scores() == null || response.scores().size() != items.size()) {
            throw new MlServiceUnavailableException("ML service returned an incomplete batch", null);
        }
        return response.scores();
    }

    public MlRiskApi.ActiveModels activeModels() {
        return call("active models", () -> rest.get().uri("/v1/models/active").retrieve()
                .body(MlRiskApi.ActiveModels.class));
    }

    /** Submits a simulation job (the ML service answers 202 with the job id). */
    public MlSimulationApi.Job startSimulation(MlSimulationApi.SimulationRequest request) {
        return call("start simulation", () -> rest.post().uri("/v1/simulations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MlSimulationApi.Job.class));
    }

    /**
     * Status (and result, once finished) of a simulation job.
     *
     * @throws HttpClientErrorException.NotFound if the ML service does not know the job (e.g. it was restarted)
     */
    public MlSimulationApi.Job simulation(String jobId) {
        return call("simulation status", () -> rest.get().uri("/v1/simulations/{id}", jobId).retrieve()
                .body(MlSimulationApi.Job.class));
    }

    public MlSimulationApi.ForecastPage simulationForecasts(String jobId, int offset, int limit) {
        return call("simulation forecasts", () -> rest.get()
                .uri("/v1/simulations/{id}/forecasts?offset={offset}&limit={limit}", jobId, offset, limit)
                .retrieve()
                .body(MlSimulationApi.ForecastPage.class));
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    private <T> T call(String operation, Supplier<T> request) {
        Supplier<T> guarded = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, request));
        try {
            return guarded.get();
        } catch (HttpClientErrorException e) {
            throw e;
        } catch (CallNotPermittedException e) {
            throw new MlServiceUnavailableException("ML service circuit breaker is open (" + operation + ")", e);
        } catch (RestClientException e) {
            throw new MlServiceUnavailableException("ML service call failed (" + operation + "): " + e.getMessage(), e);
        }
    }
}

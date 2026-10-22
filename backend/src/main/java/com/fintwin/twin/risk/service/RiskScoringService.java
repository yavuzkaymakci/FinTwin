package com.fintwin.twin.risk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fintwin.ml.MlRiskApi;
import com.fintwin.ml.MlServiceClient;
import com.fintwin.ml.MlServiceProperties;
import com.fintwin.twin.risk.dto.PortfolioRiskSummary;
import com.fintwin.twin.risk.dto.RiskScoreView;
import com.fintwin.twin.risk.dto.ScoringStatus;
import com.fintwin.twin.risk.store.RiskScoreRepository;
import com.fintwin.twin.risk.store.RiskScoreRepository.StoredScore;
import com.fintwin.twin.risk.store.RiskScoreRepository.TwinInput;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scores twins with the ML service and stores the results. Portfolio scoring runs on one background thread in
 * batches; every row of a run shares the same {@code scored_at}, so a run is identifiable and auditable.
 */
@Service
public class RiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

    private final RiskScoreRepository repository;
    private final MlServiceClient client;
    private final MlServiceProperties properties;
    private final ObjectMapper json;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "portfolio-risk-scoring");
        t.setDaemon(true);
        return t;
    });

    private volatile String state = "IDLE";
    private volatile Instant scoredAt;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile long twins;
    private volatile String error;
    private final AtomicLong scored = new AtomicLong();

    public RiskScoringService(RiskScoreRepository repository, MlServiceClient client, MlServiceProperties properties,
                              ObjectMapper json) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
        this.json = json;
    }

    /** Starts scoring every twin in the background. */
    public synchronized ScoringStatus startPortfolioScoring() {
        if ("RUNNING".equals(state)) {
            throw new IllegalStateException("Portfolio scoring is already running");
        }
        state = "RUNNING";
        scoredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        startedAt = Instant.now();
        finishedAt = null;
        error = null;
        scored.set(0);
        twins = repository.countTwins();
        executor.submit(this::scorePortfolio);
        return status();
    }

    /** Scores the whole portfolio synchronously (used by the background job and tests). */
    void scorePortfolio() {
        try {
            long after = 0;
            while (true) {
                List<TwinInput> page = repository.twinsAfter(after, properties.batchSize());
                if (page.isEmpty()) {
                    break;
                }
                repository.saveAll(score(page, scoredAt));
                scored.addAndGet(page.size());
                after = page.getLast().customerId();
            }
            state = "SUCCEEDED";
            log.info("Scored {} twins in {} s", scored.get(), Duration.between(startedAt, Instant.now()).toSeconds());
        } catch (RuntimeException e) {
            state = "FAILED";
            error = e.getMessage();
            log.error("Portfolio scoring failed after {} twins", scored.get(), e);
        } finally {
            finishedAt = Instant.now();
        }
    }

    /** Latest stored score of a customer. */
    public Optional<RiskScoreView> latest(long customerId) {
        return repository.latest(customerId).map(this::view);
    }

    /**
     * Scores one customer now and stores the result.
     *
     * @throws com.fintwin.ml.MlServiceUnavailableException if the ML service is unavailable
     */
    public Optional<RiskScoreView> scoreNow(long customerId) {
        Optional<TwinInput> twin = repository.twin(customerId);
        if (twin.isEmpty()) {
            return Optional.empty();
        }
        StoredScore score = score(List.of(twin.get()), Instant.now().truncatedTo(ChronoUnit.MICROS)).getFirst();
        repository.saveAll(List.of(score));
        return Optional.of(view(score));
    }

    /** Summary of the latest portfolio scoring run, if the portfolio has been scored. */
    public Optional<PortfolioRiskSummary> portfolioSummary() {
        return repository.latestRun().map(run -> new PortfolioRiskSummary(run.scoredAt(), run.customers(),
                run.meanPd(), run.meanChurn(), repository.gradeCounts(run.scoredAt()),
                repository.segmentAverages(run.scoredAt()).entrySet().stream()
                        .map(e -> new PortfolioRiskSummary.Segment(e.getKey(), (long) e.getValue()[2],
                                e.getValue()[0], e.getValue()[1]))
                        .toList(),
                run.pdModelVersion(), run.churnModelVersion()));
    }

    public ScoringStatus status() {
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return new ScoringStatus(state, scoredAt, twins, scored.get(), startedAt, finishedAt,
                startedAt == null ? null : Duration.between(startedAt, end).toMillis() / 1000.0, error,
                client.circuitState().name());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private List<StoredScore> score(List<TwinInput> twinsToScore, Instant runAt) {
        List<MlRiskApi.ScoreRequest> requests = new ArrayList<>(twinsToScore.size());
        for (TwinInput t : twinsToScore) {
            requests.add(new MlRiskApi.ScoreRequest(t.customerId(), t.segment(), readTree(t.featuresJson())));
        }
        List<MlRiskApi.Score> results = client.scoreBatch(requests);
        List<StoredScore> scores = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            MlRiskApi.Score s = results.get(i);
            TwinInput t = twinsToScore.get(i);
            if (s.customerId() != t.customerId()) {
                throw new IllegalStateException("ML service returned scores out of order");
            }
            ObjectNode reasons = json.createObjectNode();
            reasons.set("pd", json.valueToTree(s.pdReasons()));
            reasons.set("churn", json.valueToTree(s.churnReasons()));
            scores.add(new StoredScore(s.customerId(), runAt, t.featuresAsOf(), s.pd(), s.churn(), s.riskGrade(),
                    reasons.toString(), s.pdModelVersion(), s.churnModelVersion()));
        }
        return scores;
    }

    private RiskScoreView view(StoredScore s) {
        return new RiskScoreView(s.customerId(), s.pd(), s.churn(), s.riskGrade(), readTree(s.reasonsJson()),
                s.pdModelVersion(), s.churnModelVersion(), s.scoredAt(), s.featuresAsOf());
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in twin data", e);
        }
    }
}

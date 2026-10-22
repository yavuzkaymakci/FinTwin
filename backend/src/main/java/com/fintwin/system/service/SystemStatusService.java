package com.fintwin.system.service;

import com.fintwin.assistant.service.AssistantService;
import com.fintwin.feed.dto.ReplayStatus;
import com.fintwin.feed.publish.ReplayService;
import com.fintwin.ml.MlRiskApi;
import com.fintwin.ml.MlServiceClient;
import com.fintwin.ml.MlServiceUnavailableException;
import com.fintwin.system.dto.SystemStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Aggregated status for the UI. The ML service is asked at most every 10 seconds (the answer is cached), through the
 * same resilient client as everything else, so an outage shows up as "unreachable" and the circuit breaker state.
 */
@Service
public class SystemStatusService {

    private static final Duration ML_CACHE = Duration.ofSeconds(10);

    private final MlServiceClient ml;
    private final AssistantService assistants;
    private final ReplayService replay;
    private volatile SystemStatus.Ml cachedMl;
    private volatile Instant cachedAt = Instant.EPOCH;
    private volatile List<SystemStatus.Model> lastModels = List.of();

    public SystemStatusService(MlServiceClient ml, AssistantService assistants, ReplayService replay) {
        this.ml = ml;
        this.assistants = assistants;
        this.replay = replay;
    }

    public SystemStatus status() {
        ReplayStatus r = replay.status();
        return new SystemStatus(Instant.now(), ml(), assistants.provider(),
                new SystemStatus.Replay(r.mode(), r.simulatedTime(), r.secondsPerDay()));
    }

    private SystemStatus.Ml ml() {
        Instant now = Instant.now();
        SystemStatus.Ml cached = cachedMl;
        if (cached != null && cachedAt.plus(ML_CACHE).isAfter(now)) {
            return new SystemStatus.Ml(cached.reachable(), ml.circuitState().name(), cached.error(), cached.models());
        }
        SystemStatus.Ml fresh;
        try {
            MlRiskApi.ActiveModels active = ml.activeModels();
            List<SystemStatus.Model> models = active == null || active.models() == null ? List.of()
                    : active.models().stream().map(m -> new SystemStatus.Model(m.name(), m.version(), m.trainedAt(),
                    m.summary())).toList();
            fresh = new SystemStatus.Ml(true, ml.circuitState().name(), null, models);
        } catch (MlServiceUnavailableException e) {
            fresh = new SystemStatus.Ml(false, ml.circuitState().name(), e.getMessage(), lastModels);
        }
        // Only successes are cached; while the service is down, the circuit breaker limits the calls.
        cachedMl = fresh.reachable() ? fresh : null;
        if (fresh.reachable()) {
            lastModels = fresh.models();  // shown (as last known) while the service is down
        }
        cachedAt = now;
        return fresh;
    }
}

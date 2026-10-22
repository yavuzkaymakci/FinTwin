package com.fintwin.simulation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the ML service for active simulation runs ({@code fintwin.simulation.polling.*}; off in tests). */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "fintwin.simulation.polling.enabled", havingValue = "true", matchIfMissing = true)
public class SimulationPoller {

    private final SimulationService simulations;

    public SimulationPoller(SimulationService simulations) {
        this.simulations = simulations;
    }

    @Scheduled(fixedDelayString = "${fintwin.simulation.polling.interval:5s}",
            initialDelayString = "${fintwin.simulation.polling.interval:5s}")
    public void poll() {
        simulations.pollActiveRuns();
    }
}

package com.fintwin.simulation.dto;

import java.time.Instant;

public record ScenarioView(long id, String code, String name, String description, ScenarioParameters parameters,
                           boolean preset, Instant createdAt, Instant updatedAt) {
}

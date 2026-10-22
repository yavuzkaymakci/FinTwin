package com.fintwin.simulation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create or update a (non-preset) scenario. */
public record ScenarioRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}", message = "upper-case code, e.g. MY_FX_SHOCK") String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 2000) String description,
        @NotNull @Valid ScenarioParameters parameters) {
}

package com.fintwin.simulation.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;

/**
 * A macroeconomic scenario over the simulation horizon, relative to the starting month. Mirrors the ML service's
 * Pydantic {@code ScenarioParameters} (app/simulation/scenario.py) field by field; it is also the tool schema the
 * scenario assistant fills from natural language, so every field states its unit and range. Omitted optional
 * fields take the defaults below.
 */
@JsonClassDescription("Macroeconomic stress scenario applied to the retail portfolio's digital twins")
public record ScenarioParameters(
        @JsonPropertyDescription("Simulation horizon in months (1-36, default 12)")
        @Min(1) @Max(36) Integer horizonMonths,

        @JsonPropertyDescription("Change of the USD/TRY rate against the starting month, in percent (30 = lira "
                + "loses 30 %, i.e. USD/TRY x1.30), reached at once or over shockRampMonths; range -50..200")
        @DecimalMin("-50") @DecimalMax("200") Double usdTryChangePct,

        @JsonPropertyDescription("Change of the CBRT annual policy rate against the starting month, in basis points "
                + "(500 = +5 percentage points); card and deposit rates follow it; range -3000..3000")
        @Min(-3000) @Max(3000) Integer policyRateChangeBp,

        @JsonPropertyDescription("Monthly CPI inflation path in percent per month, starting with the first "
                + "simulated month; the last value repeats until the horizon, e.g. [4.5, 3.0, 2.0]; each -5..30")
        @NotEmpty @Size(max = 36)
        List<@NotNull @DecimalMin("-5") @DecimalMax("30") Double> monthlyInflationPct,

        @JsonPropertyDescription("Share of the inflation since the previous raise that incomes receive at each raise, "
                + "in percent (80 = raises cover 80 % of past inflation); 0..150, default 90")
        @DecimalMin("0") @DecimalMax("150") Double wageIndexationPct,

        @JsonPropertyDescription("Months between income raises, aligned to January (6 = January and July, 12 = "
                + "January only); 0 or 1 = monthly indexation; 0..12, default 6")
        @Min(0) @Max(12) Integer wageAdjustmentLagMonths,

        @JsonPropertyDescription("Optional per-segment overrides of the wage rule")
        @Size(max = 5) List<@Valid @NotNull SegmentWageRule> segmentWageRules,

        @JsonPropertyDescription("IMMEDIATE: USD/TRY and policy rate changes apply from the first month; GRADUAL: "
                + "they build up linearly over shockRampMonths")
        ShockProfile shockProfile,

        @JsonPropertyDescription("Months to reach the full change when GRADUAL (1-36, default 6)")
        @Min(1) @Max(36) Integer shockRampMonths,

        @JsonPropertyDescription("Number of Monte Carlo paths (10-2000, default 200)")
        @Min(10) @Max(2000) Integer monteCarloPaths,

        @JsonPropertyDescription("Random seed; the same scenario and seed give the same result (default 42)")
        @Min(0) @Max(Integer.MAX_VALUE) Long seed) {

    public enum ShockProfile { IMMEDIATE, GRADUAL }

    @JsonClassDescription("Wage rule for one customer segment, overriding the scenario-wide rule")
    public record SegmentWageRule(
            @JsonPropertyDescription("MIN_WAGE, RETIREE, SELF_EMPLOYED, STUDENT or WHITE_COLLAR")
            @NotNull @Pattern(regexp = "MIN_WAGE|RETIREE|SELF_EMPLOYED|STUDENT|WHITE_COLLAR") String segment,
            @JsonPropertyDescription("Share of past inflation passed into incomes, percent (0-150)")
            @NotNull @DecimalMin("0") @DecimalMax("150") Double wageIndexationPct,
            @JsonPropertyDescription("Months between raises (0-12, see wageAdjustmentLagMonths)")
            @NotNull @Min(0) @Max(12) Integer wageAdjustmentLagMonths) {
    }

    public ScenarioParameters {
        horizonMonths = horizonMonths == null ? 12 : horizonMonths;
        usdTryChangePct = usdTryChangePct == null ? 0.0 : usdTryChangePct;
        policyRateChangeBp = policyRateChangeBp == null ? 0 : policyRateChangeBp;
        monthlyInflationPct = monthlyInflationPct == null ? List.of() : List.copyOf(monthlyInflationPct);
        wageIndexationPct = wageIndexationPct == null ? 90.0 : wageIndexationPct;
        wageAdjustmentLagMonths = wageAdjustmentLagMonths == null ? 6 : wageAdjustmentLagMonths;
        segmentWageRules = segmentWageRules == null ? List.of() : List.copyOf(segmentWageRules);
        shockProfile = shockProfile == null ? ShockProfile.IMMEDIATE : shockProfile;
        shockRampMonths = shockRampMonths == null ? 6 : shockRampMonths;
        monteCarloPaths = monteCarloPaths == null ? 200 : monteCarloPaths;
        seed = seed == null ? 42L : seed;
    }

    @AssertTrue(message = "monthlyInflationPct has more values than horizonMonths")
    boolean isInflationPathWithinHorizon() {
        return monthlyInflationPct.size() <= horizonMonths;
    }

    @AssertTrue(message = "segmentWageRules lists a segment twice")
    boolean isSegmentRulesUnique() {
        return new HashSet<>(segmentWageRules.stream().map(SegmentWageRule::segment).toList()).size()
                == segmentWageRules.size();
    }
}

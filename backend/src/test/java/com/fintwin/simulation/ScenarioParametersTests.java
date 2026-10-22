package com.fintwin.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.simulation.dto.ScenarioParameters;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioParametersTests {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper json = new ObjectMapper();

    private Set<String> violations(String body) throws Exception {
        ScenarioParameters parameters = json.readValue(body, ScenarioParameters.class);
        return VALIDATOR.validate(parameters).stream().map(ConstraintViolation::getPropertyPath)
                .map(Object::toString).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void omittedFieldsTakeTheDefaultsOfTheMlService() throws Exception {
        ScenarioParameters p = json.readValue("{\"monthlyInflationPct\": [2.5]}", ScenarioParameters.class);
        assertThat(VALIDATOR.validate(p)).isEmpty();
        assertThat(p.horizonMonths()).isEqualTo(12);
        assertThat(p.wageIndexationPct()).isEqualTo(90.0);
        assertThat(p.wageAdjustmentLagMonths()).isEqualTo(6);
        assertThat(p.shockProfile()).isEqualTo(ScenarioParameters.ShockProfile.IMMEDIATE);
        assertThat(p.monteCarloPaths()).isEqualTo(200);
        assertThat(p.seed()).isEqualTo(42L);
        assertThat(p.segmentWageRules()).isEmpty();
    }

    @Test
    void serialisesExactlyTheFieldsTheMlServiceAccepts() {
        JsonNode node = json.valueToTree(new ScenarioParameters(6, 30.0, 500, List.of(4.0), null, null, null,
                ScenarioParameters.ShockProfile.GRADUAL, 3, 50, 7L));
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        // The Pydantic model forbids unknown fields: validation helpers must not leak into the JSON.
        assertThat(names).containsExactlyInAnyOrder("horizonMonths", "usdTryChangePct", "policyRateChangeBp",
                "monthlyInflationPct", "wageIndexationPct", "wageAdjustmentLagMonths", "segmentWageRules",
                "shockProfile", "shockRampMonths", "monteCarloPaths", "seed");
        assertThat(node.get("shockProfile").asText()).isEqualTo("GRADUAL");
    }

    @Test
    void rejectsOutOfRangeAndInconsistentValues() throws Exception {
        assertThat(violations("{\"monthlyInflationPct\": []}")).contains("monthlyInflationPct");
        assertThat(violations("{\"monthlyInflationPct\": [2], \"horizonMonths\": 0}")).contains("horizonMonths");
        assertThat(violations("{\"monthlyInflationPct\": [2], \"usdTryChangePct\": 500}"))
                .contains("usdTryChangePct");
        assertThat(violations("{\"monthlyInflationPct\": [2], \"policyRateChangeBp\": 99999}"))
                .contains("policyRateChangeBp");
        assertThat(violations("{\"monthlyInflationPct\": [2, 2, 2], \"horizonMonths\": 2}"))
                .contains("inflationPathWithinHorizon");
        assertThat(violations("{\"monthlyInflationPct\": [40]}")).anyMatch(v -> v.startsWith("monthlyInflationPct"));
        assertThat(violations("{\"monthlyInflationPct\": [2], \"monteCarloPaths\": 5}")).contains("monteCarloPaths");
        assertThat(violations("""
                {"monthlyInflationPct": [2], "segmentWageRules": [
                  {"segment": "PILOT", "wageIndexationPct": 50, "wageAdjustmentLagMonths": 6}]}"""))
                .anyMatch(v -> v.startsWith("segmentWageRules"));
        assertThat(violations("""
                {"monthlyInflationPct": [2], "segmentWageRules": [
                  {"segment": "RETIREE", "wageIndexationPct": 100, "wageAdjustmentLagMonths": 6},
                  {"segment": "RETIREE", "wageIndexationPct": 90, "wageAdjustmentLagMonths": 6}]}"""))
                .contains("segmentRulesUnique");
    }
}

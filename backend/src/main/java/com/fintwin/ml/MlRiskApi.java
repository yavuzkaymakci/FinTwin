package com.fintwin.ml;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Wire format of the ML service's risk endpoints (camelCase JSON, see ml-service app/api/schemas.py). */
public final class MlRiskApi {

    private MlRiskApi() {
    }

    /** {@code features} is the twin's TwinFeatures JSON, passed through unchanged. */
    public record ScoreRequest(long customerId, String segment, JsonNode features) {
    }

    public record BatchRequest(List<ScoreRequest> items) {
    }

    public record Score(long customerId, double pd, double churn, int riskGrade, boolean pdApplicable,
                        List<JsonNode> pdReasons, List<JsonNode> churnReasons, String pdModelVersion,
                        String churnModelVersion) {
    }

    public record BatchResponse(List<Score> scores) {
    }

    public record ModelInfo(String name, String version, String trainedAt, JsonNode summary) {
    }

    public record ActiveModels(List<ModelInfo> models) {
    }
}

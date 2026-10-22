package com.fintwin.twin.risk.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * A twin's risk score.
 *
 * @param reasons {@code {"pd": [...], "churn": [...]}}: top reasons with code, feature, value, portfolio mean,
 *                SHAP impact and readable texts (English {@code text}, Turkish {@code textTr})
 */
public record RiskScoreView(long customerId, double pd, double churn, int riskGrade, JsonNode reasons,
                            String pdModelVersion, String churnModelVersion, Instant scoredAt, Instant featuresAsOf) {
}

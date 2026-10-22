"""PD and churn scoring with rating grades and reasons."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from app.features import from_twin_features, has_credit_exposure
from app.grades import grade
from app.models.matrix import to_matrix
from app.models.risk_artifact import RiskModelArtifact
from app.scoring.reasons import top_reasons


@dataclass(frozen=True)
class ScoringInput:
    customer_id: int
    segment: str | None
    twin_features: Mapping[str, Any]


class RiskScorer:
    def __init__(self, pd_model: RiskModelArtifact, churn_model: RiskModelArtifact) -> None:
        self.pd_model = pd_model
        self.churn_model = churn_model

    def score(self, inputs: Sequence[ScoringInput], explain_top: int = 5, full: bool = False) -> list[dict[str, Any]]:
        if not inputs:
            return []
        rows = [from_twin_features(i.twin_features, i.segment) for i in inputs]
        x = to_matrix(rows)
        pd_probability = self.pd_model.probability(x)
        churn_probability = self.churn_model.probability(x)
        pd_contrib = self.pd_model.contributions(x)
        churn_contrib = self.churn_model.contributions(x)
        results = []
        for i, item in enumerate(inputs):
            pd_c = pd_contrib.iloc[i].to_dict()
            churn_c = churn_contrib.iloc[i].to_dict()
            result: dict[str, Any] = {
                "customer_id": item.customer_id,
                "pd": float(pd_probability[i]),
                "churn": float(churn_probability[i]),
                "risk_grade": grade(float(pd_probability[i])),
                "pd_applicable": has_credit_exposure(item.twin_features),
                "pd_reasons": top_reasons(pd_c, rows[i], self.pd_model.portfolio_means,
                                          self.pd_model.segment_rates, explain_top),
                "churn_reasons": top_reasons(churn_c, rows[i], self.churn_model.portfolio_means,
                                             self.churn_model.segment_rates, explain_top),
                "pd_model_version": self.pd_model.version,
                "churn_model_version": self.churn_model.version,
            }
            if full:
                result["pd_contributions"] = {k: float(v) for k, v in pd_c.items()}
                result["churn_contributions"] = {k: float(v) for k, v in churn_c.items()}
                result["model_features"] = rows[i]
            results.append(result)
        return results

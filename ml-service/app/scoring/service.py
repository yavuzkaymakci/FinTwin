"""Loads the active models from the registry once per process (FastAPI dependency)."""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from typing import Any

from app.models import registry
from app.models.nlp_artifact import CategorizerArtifact
from app.scoring.risk_scorer import RiskScorer


class ModelUnavailableError(RuntimeError):
    pass


@dataclass
class LoadedModels:
    risk: RiskScorer | None
    categorizer: CategorizerArtifact | None
    metadata: dict[str, dict[str, Any]]

    def require_risk(self) -> RiskScorer:
        if self.risk is None:
            raise ModelUnavailableError("No active PD/churn models; train them (make ml-train)")
        return self.risk

    def require_categorizer(self) -> CategorizerArtifact:
        if self.categorizer is None:
            raise ModelUnavailableError("No active categorisation model; train it (make ml-train-nlp)")
        return self.categorizer


def load_models() -> LoadedModels:
    metadata: dict[str, dict[str, Any]] = {}
    loaded: dict[str, Any] = {}
    for name in registry.MODEL_NAMES:
        try:
            loaded[name], metadata[name] = registry.load(name)
        except FileNotFoundError:
            continue
    risk = RiskScorer(loaded["pd"], loaded["churn"]) if "pd" in loaded and "churn" in loaded else None
    return LoadedModels(risk=risk, categorizer=loaded.get("nlp"), metadata=metadata)


@lru_cache(maxsize=1)
def get_models() -> LoadedModels:
    return load_models()

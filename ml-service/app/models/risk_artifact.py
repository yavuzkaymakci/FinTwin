"""Serialized PD / churn model: champion (LightGBM + isotonic calibration), challenger (logistic regression +
Platt scaling), portfolio statistics for explanations and the rating scale."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import numpy as np
import pandas as pd

from app.grades import grade
from app.models.matrix import MATRIX_COLUMNS, feature_of, to_matrix


@dataclass
class RiskModelArtifact:
    target: str
    version: str
    booster: Any  # lightgbm.LGBMClassifier
    calibrator: Any  # sklearn IsotonicRegression on the raw booster probability
    challenger: Any = None  # sklearn Pipeline (logistic regression)
    challenger_calibrator: Any = None  # Platt scaling of the challenger
    #: Training-population mean of every numeric feature (for readable explanations).
    portfolio_means: dict[str, float] = field(default_factory=dict)
    #: Training event rate per segment.
    segment_rates: dict[str, float] = field(default_factory=dict)
    _explainer: Any = field(default=None, repr=False)

    def __getstate__(self) -> dict[str, Any]:
        state = self.__dict__.copy()
        state["_explainer"] = None  # rebuilt lazily after loading
        return state

    def matrix(self, rows: pd.DataFrame | list[dict[str, Any]]) -> pd.DataFrame:
        return to_matrix(rows)

    def raw_probability(self, x: pd.DataFrame) -> np.ndarray:
        return self.booster.predict_proba(x[MATRIX_COLUMNS])[:, 1]

    def probability(self, x: pd.DataFrame) -> np.ndarray:
        """Calibrated probability of the target event within the horizon."""
        return np.clip(self.calibrator.predict(self.raw_probability(x)), 1e-5, 1 - 1e-5)

    def challenger_probability(self, x: pd.DataFrame) -> np.ndarray:
        raw = self.challenger.predict_proba(x[MATRIX_COLUMNS])[:, 1]
        return self.challenger_calibrator.predict_proba(logit(raw).reshape(-1, 1))[:, 1]

    def grades(self, probabilities: np.ndarray) -> list[int]:
        return [grade(float(p)) for p in probabilities]

    def contributions(self, x: pd.DataFrame) -> pd.DataFrame:
        """SHAP values (TreeSHAP, log-odds of the uncalibrated model) per model feature; segment dummies summed."""
        import shap

        if self._explainer is None:
            self._explainer = shap.TreeExplainer(self.booster)
        values = self._explainer.shap_values(x[MATRIX_COLUMNS])
        if isinstance(values, list):  # older shap: one array per class
            values = values[1]
        per_column = pd.DataFrame(np.asarray(values), columns=MATRIX_COLUMNS, index=x.index)
        return per_column.T.groupby(feature_of).sum().T


def logit(p: np.ndarray) -> np.ndarray:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return np.log(p / (1 - p))


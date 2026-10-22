"""Fitting and evaluation of the PD / churn models.

Champion: LightGBM (early stopping on the validation period) + isotonic calibration fitted on the validation
period. Challenger / baseline: logistic regression (median imputation with missing indicators, standardisation)
+ Platt scaling. Both are evaluated on the out-of-time test period.
"""

from __future__ import annotations

from typing import Any

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.impute import SimpleImputer
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from app.features import NUMERIC_FEATURES, SEGMENTS
from app.grades import grade, grade_ranges
from app.models.matrix import MATRIX_COLUMNS, to_matrix
from app.models.risk_artifact import RiskModelArtifact, logit
from app.training.metrics import calibration_table, discrimination, psi

LGBM_PARAMS: dict[str, Any] = {
    "n_estimators": 2000,
    "learning_rate": 0.02,
    "num_leaves": 15,
    "max_depth": 5,
    "min_child_samples": 100,
    "subsample": 0.8,
    "subsample_freq": 1,
    "colsample_bytree": 0.8,
    "reg_lambda": 5.0,
    "random_state": 42,
    "n_jobs": 2,
    "verbose": -1,
}
LOGISTIC_C = 0.3
EARLY_STOPPING_ROUNDS = 100


def fit_champion(x_train: pd.DataFrame, y_train: np.ndarray, x_val: pd.DataFrame, y_val: np.ndarray
                 ) -> lgb.LGBMClassifier:
    model = lgb.LGBMClassifier(**LGBM_PARAMS)
    model.fit(x_train, y_train, eval_set=[(x_val, y_val)], eval_metric="binary_logloss",
              callbacks=[lgb.early_stopping(EARLY_STOPPING_ROUNDS, verbose=False)])
    return model


def fit_challenger(x_train: pd.DataFrame, y_train: np.ndarray) -> Pipeline:
    pipeline = Pipeline([
        ("impute", SimpleImputer(strategy="median", add_indicator=True)),
        ("scale", StandardScaler()),
        ("model", LogisticRegression(C=LOGISTIC_C, class_weight="balanced", max_iter=5000)),
    ])
    pipeline.fit(x_train, y_train)
    return pipeline


def fit_isotonic(raw: np.ndarray, y: np.ndarray) -> IsotonicRegression:
    calibrator = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
    calibrator.fit(raw, y)
    return calibrator


def fit_platt(raw: np.ndarray, y: np.ndarray) -> LogisticRegression:
    calibrator = LogisticRegression(C=1e6, max_iter=1000)
    calibrator.fit(logit(raw).reshape(-1, 1), y)
    return calibrator


def train(target: str, version: str, parts: dict[str, pd.DataFrame]) -> tuple[RiskModelArtifact, dict[str, Any]]:
    """Fits champion and challenger and returns the artifact plus an evaluation report."""
    x = {name: to_matrix(frame) for name, frame in parts.items()}
    y = {name: frame["label"].to_numpy() for name, frame in parts.items()}

    booster = fit_champion(x["train"], y["train"], x["validation"], y["validation"])
    calibrator = fit_isotonic(booster.predict_proba(x["validation"])[:, 1], y["validation"])
    challenger = fit_challenger(x["train"], y["train"])
    challenger_calibrator = fit_platt(challenger.predict_proba(x["validation"])[:, 1], y["validation"])

    train_frame = parts["train"]
    artifact = RiskModelArtifact(
        target=target, version=version, booster=booster, calibrator=calibrator, challenger=challenger,
        challenger_calibrator=challenger_calibrator,
        portfolio_means={c: float(np.nanmean(x["train"][c])) for c in NUMERIC_FEATURES},
        segment_rates={s: float(train_frame.loc[train_frame["segment"] == s, "label"].mean())
                       for s in SEGMENTS if (train_frame["segment"] == s).any()},
    )
    return artifact, evaluate(artifact, parts, x, y)


def evaluate(artifact: RiskModelArtifact, parts: dict[str, pd.DataFrame], x: dict[str, pd.DataFrame],
             y: dict[str, np.ndarray]) -> dict[str, Any]:
    report: dict[str, Any] = {"best_iteration": int(artifact.booster.best_iteration_ or 0)}
    predictions: dict[str, dict[str, np.ndarray]] = {}
    for name in parts:
        raw = artifact.raw_probability(x[name])
        predictions[name] = {
            "lightgbm_raw": raw,
            "lightgbm": artifact.probability(x[name]),
            "logistic": artifact.challenger_probability(x[name]),
        }
    report["metrics"] = {model: {name: discrimination(y[name], predictions[name][model]) for name in parts}
                         for model in ("lightgbm", "lightgbm_raw", "logistic")}

    test = parts["test"]
    p_test = predictions["test"]["lightgbm"]
    # Behavioural scores are dominated by customers already in arrears; banks also report the "performing" book.
    performing = (test["days_since_last_late"].to_numpy() > 90)
    report["performing_test"] = {
        "definition": "no late payment in the 90 days before the observation date",
        "lightgbm": discrimination(y["test"][performing], p_test[performing]),
        "logistic": discrimination(y["test"][performing], predictions["test"]["logistic"][performing]),
    }
    report["calibration_test"] = {
        "lightgbm": calibration_table(y["test"], p_test),
        "logistic": calibration_table(y["test"], predictions["test"]["logistic"]),
        "mean_predicted": float(p_test.mean()),
        "observed_rate": float(y["test"].mean()),
    }
    report["segments_test"] = {}
    for segment in SEGMENTS:
        mask = (test["segment"] == segment).to_numpy()
        if mask.any():
            report["segments_test"][segment] = {
                "lightgbm": discrimination(y["test"][mask], p_test[mask]),
                "logistic": discrimination(y["test"][mask], predictions["test"]["logistic"][mask]),
            }
    grades = np.array([grade(float(p)) for p in p_test])
    report["grades_test"] = [
        {**r, "rows": int((grades == r["grade"]).sum()),
         "events": int(y["test"][grades == r["grade"]].sum()),
         "observed_rate": float(y["test"][grades == r["grade"]].mean()) if (grades == r["grade"]).any() else None}
        for r in grade_ranges()]

    train_scores = predictions["train"]["lightgbm"]
    report["psi"] = {
        "score": {name: psi(train_scores, predictions[name]["lightgbm"]) for name in ("validation", "test")},
        "features": {c: {name: psi(x["train"][c].to_numpy(), x[name][c].to_numpy())
                         for name in ("validation", "test")} for c in NUMERIC_FEATURES},
    }

    sample = x["test"].sample(n=min(5000, len(x["test"])), random_state=7)
    contributions = artifact.contributions(sample)
    importance = contributions.abs().mean().sort_values(ascending=False)
    report["shap_importance_test"] = {k: float(v) for k, v in importance.items()}
    report["lgbm_gain_importance"] = dict(sorted(
        zip(MATRIX_COLUMNS, artifact.booster.booster_.feature_importance("gain").tolist(), strict=True),
        key=lambda kv: -kv[1]))
    report["test_predictions"] = pd.DataFrame({
        "customer_id": test["customer_id"].to_numpy(), "as_of": test["as_of"].to_numpy(),
        "segment": test["segment"].astype(str).to_numpy(), "label": y["test"],
        "probability": p_test, "challenger_probability": predictions["test"]["logistic"]})
    return report

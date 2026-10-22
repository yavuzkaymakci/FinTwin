"""Credit-risk model metrics: discrimination (AUC, Gini, KS), accuracy of probabilities (Brier, calibration) and
population stability (PSI)."""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.metrics import brier_score_loss, roc_auc_score, roc_curve


def discrimination(y: np.ndarray, p: np.ndarray) -> dict[str, float | int | None]:
    y = np.asarray(y)
    p = np.asarray(p, dtype=float)
    result: dict[str, float | int | None] = {
        "rows": int(len(y)),
        "events": int(y.sum()),
        "event_rate": float(y.mean()) if len(y) else None,
        "mean_prediction": float(p.mean()) if len(p) else None,
        "brier": float(brier_score_loss(y, p)) if len(y) else None,
    }
    if 0 < y.sum() < len(y):
        auc = float(roc_auc_score(y, p))
        fpr, tpr, _ = roc_curve(y, p)
        result.update(auc=auc, gini=2 * auc - 1, ks=float(np.max(tpr - fpr)))
    else:
        result.update(auc=None, gini=None, ks=None)
    return result


def calibration_table(y: np.ndarray, p: np.ndarray, bins: int = 10) -> list[dict[str, float | int]]:
    """Mean prediction vs observed rate per prediction decile."""
    frame = pd.DataFrame({"y": np.asarray(y), "p": np.asarray(p, dtype=float)})
    frame["bin"] = pd.qcut(frame["p"].rank(method="first"), q=bins, labels=False)
    table = frame.groupby("bin").agg(rows=("y", "size"), events=("y", "sum"), mean_prediction=("p", "mean"),
                                     observed_rate=("y", "mean"))
    return [{"bin": int(b) + 1, **{k: (int(v) if k in ("rows", "events") else float(v)) for k, v in r.items()}}
            for b, r in table.iterrows()]


def psi(expected: np.ndarray, actual: np.ndarray, bins: int = 10) -> float:
    """Population stability index of ``actual`` against ``expected`` (quantile bins of the expected sample).
    < 0.1 stable, 0.1–0.25 moderate shift, > 0.25 significant shift."""
    expected = np.asarray(expected, dtype=float)
    actual = np.asarray(actual, dtype=float)
    expected = expected[~np.isnan(expected)]
    actual = actual[~np.isnan(actual)]
    if len(expected) == 0 or len(actual) == 0:
        return float("nan")
    edges = np.unique(np.quantile(expected, np.linspace(0, 1, bins + 1)))
    if len(edges) < 2:
        return 0.0
    edges[0], edges[-1] = -np.inf, np.inf
    e = np.histogram(expected, edges)[0] / len(expected)
    a = np.histogram(actual, edges)[0] / len(actual)
    e = np.clip(e, 1e-6, None)
    a = np.clip(a, 1e-6, None)
    return float(np.sum((a - e) * np.log(a / e)))

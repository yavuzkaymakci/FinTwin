"""How well does the predicted PD recover the generator's hidden financial stress?

    python -m app.evaluation.stress_relationship [--version <pd version>]

Joins the PD model's out-of-time test predictions (customer x observation month) with
``ground_truth.customer_monthly_state.financial_stress`` at the end of the month before the observation date — the
hidden state the model could not see — and reports rank correlation, mean PD by stress decile, and how the hidden
stress itself would have discriminated the same outcomes (an upper reference for the model).
"""

from __future__ import annotations

import argparse
import json
import logging

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

from app.db import read_frame
from app.labeling import labels_db_url
from app.models import registry

log = logging.getLogger("stress_relationship")

STRESS_SQL = """
SELECT customer_id, period, financial_stress, p_miss_payment, debt_to_income, card_utilization
FROM ground_truth.customer_monthly_state
"""


def analyse(predictions: pd.DataFrame, stress: pd.DataFrame) -> dict:
    predictions = predictions.copy()
    predictions["period"] = (pd.to_datetime(predictions["as_of"]) - pd.DateOffset(months=1)).dt.date
    stress = stress.copy()
    stress["period"] = pd.to_datetime(stress["period"]).dt.date
    joined = predictions.merge(stress, on=["customer_id", "period"], how="inner")
    joined["stress_decile"] = pd.qcut(joined["financial_stress"].rank(method="first"), 10, labels=False) + 1
    deciles = joined.groupby("stress_decile").agg(
        rows=("label", "size"), stress_from=("financial_stress", "min"), stress_to=("financial_stress", "max"),
        mean_pd=("probability", "mean"), observed_default_rate=("label", "mean"))
    return {
        "rows": int(len(joined)),
        "spearman_pd_vs_stress": float(joined["probability"].corr(joined["financial_stress"], method="spearman")),
        "pearson_pd_vs_stress": float(joined["probability"].corr(joined["financial_stress"])),
        "spearman_pd_vs_p_miss_payment": float(joined["probability"].corr(joined["p_miss_payment"],
                                                                          method="spearman")),
        "auc_model": float(roc_auc_score(joined["label"], joined["probability"])),
        "auc_hidden_stress": float(roc_auc_score(joined["label"], joined["financial_stress"])),
        "by_stress_decile": [{"decile": int(d), **{k: float(v) for k, v in r.items()}}
                             for d, r in deciles.iterrows()],
        "mean_pd_monotonic_in_stress": bool(np.all(np.diff(deciles["mean_pd"].to_numpy()) >= -1e-9)),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", help="PD model version (default: active)")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    version = args.version or registry.active_versions()["pd"]
    reports = registry.artifacts_dir() / "reports"
    predictions = pd.read_parquet(reports / f"pd-{version}-test-predictions.parquet")
    stress = read_frame(STRESS_SQL, url=labels_db_url())
    result = {"pd_model_version": version, **analyse(predictions, stress)}
    target = reports / f"pd-{version}-stress-relationship.json"
    target.write_text(json.dumps(result, indent=2), encoding="utf-8")
    log.info("written %s\n%s", target, json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

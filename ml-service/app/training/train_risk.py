"""Train the PD or churn model from twin snapshots and register it.

    python -m app.training.train_risk --target pd [--activate]

Long run: start detached and log to ``logs/`` (see CLAUDE.md).
"""

from __future__ import annotations

import argparse
import json
import logging
import time
from datetime import UTC, datetime
from typing import Any

from app.features import FEATURES, SPEC_BY_NAME
from app.grades import GRADE_BOUNDS
from app.models import registry
from app.training import risk_model
from app.training.dataset import HORIZON_MONTHS, TARGET_EVENTS, build_dataset, load_raw
from app.training.splits import time_split

log = logging.getLogger("train_risk")


def run(target: str, activate: bool) -> dict[str, Any]:
    t0 = time.time()
    raw = load_raw()
    log.info("loaded %d snapshots, %d events, data end %s", len(raw.snapshots), len(raw.events), raw.data_end)
    dataset = build_dataset(raw, target)
    parts, periods = time_split(dataset)
    for p in periods:
        frame = parts[p.name]
        log.info("%-10s %s rows=%d events=%d rate=%.4f", p.name, p.describe(), len(frame), frame["label"].sum(),
                 frame["label"].mean())

    version = registry.new_version()
    artifact, report = risk_model.train(target, version, parts)
    predictions = report.pop("test_predictions")
    metadata = {
        "name": target,
        "version": version,
        "trained_at": datetime.now(UTC).isoformat(),
        "target": f"{TARGET_EVENTS[target]} event within {HORIZON_MONTHS} months after the observation month",
        "population": "customers with credit exposure (card or loan)" if target == "pd" else "all active customers",
        "data": {"source": "twin.customer_twin_snapshot (features), public.customer_event (labels)",
                 "data_end": str(raw.data_end),
                 "periods": {p.name: {"from": str(p.start), "to": str(p.end), "rows": len(parts[p.name]),
                                      "events": int(parts[p.name]["label"].sum())} for p in periods}},
        "features": [{"name": f, "description": SPEC_BY_NAME[f].label_en} for f in FEATURES],
        "champion": {"algorithm": "LightGBM", "params": risk_model.LGBM_PARAMS,
                     "calibration": "isotonic regression fitted on the validation period"},
        "challenger": {"algorithm": "logistic regression", "C": risk_model.LOGISTIC_C,
                       "calibration": "Platt scaling fitted on the validation period"},
        "grade_bounds": GRADE_BOUNDS,
        "portfolio_means": artifact.portfolio_means,
        **report,
        "training_seconds": round(time.time() - t0, 1),
    }
    directory = registry.save(target, version, artifact, metadata)
    reports = registry.artifacts_dir() / "reports"
    reports.mkdir(parents=True, exist_ok=True)
    predictions.to_parquet(reports / f"{target}-{version}-test-predictions.parquet", index=False)
    if activate:
        registry.activate(target, version)
    log.info("saved %s (%s)%s", directory, version, " and activated" if activate else "")
    summary = {
        "version": version,
        "test": {m: report["metrics"][m]["test"] for m in ("lightgbm", "logistic", "lightgbm_raw")},
        "calibration": {k: report["calibration_test"][k] for k in ("mean_predicted", "observed_rate")},
        "score_psi": report["psi"]["score"],
        "top_features": list(report["shap_importance_test"].items())[:8],
    }
    log.info("summary %s", json.dumps(summary, indent=2, default=str))
    return metadata


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=sorted(TARGET_EVENTS), required=True)
    parser.add_argument("--activate", action="store_true", help="make this version the active model")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    run(args.target, args.activate)


if __name__ == "__main__":
    main()

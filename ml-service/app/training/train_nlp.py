"""Train the transaction categorisation model from raw statement texts.

    python -m app.training.train_nlp [--activate]

Labels: ``artifacts/data/transaction_labels.parquet`` from ``app.labeling.export_transaction_labels`` (the
stand-in for a hand-labelled sample). Inputs are read with the read-only ``fintwin_ml`` role: statement text, channel,
direction and account type. The model never sees the merchant category (MCC), so it learns from text alone.

Split by transaction time: train < validation (last 3 months before the test period) < test (last 6 months).
Merchant hold-out: 20 % of merchants (by id hash) are removed from training (validation keeps them, so variant
selection rewards generalisation to new merchants), so the test period
also measures generalisation to merchants the model has never seen; the "unseen descriptions" slice covers test
texts that never occur verbatim in training.
Baselines: the twin's rules with MCC (as deployed) and without MCC (text only, same information as the model).
"""

from __future__ import annotations

import argparse
import json
import logging
import time
from datetime import UTC, datetime
from typing import Any

import numpy as np
import pandas as pd
import psutil
from sklearn.compose import ColumnTransformer
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import SGDClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

from app.db import read_frame
from app.models import registry
from app.models.nlp_artifact import INPUT_COLUMNS, CategorizerArtifact, to_frame
from app.training import rules

log = logging.getLogger("train_nlp")

INPUT_SQL = """
SELECT t.id AS transaction_id, t.merchant_id, t.raw_description, t.channel, t.direction, t.currency, a.account_type,
       mc.code AS merchant_category, t.counterpart_transaction_id IS NOT NULL AS internal,
       (t.occurred_at AT TIME ZONE 'Europe/Istanbul')::date AS local_date
FROM bank_transaction t
JOIN account a ON a.id = t.account_id
LEFT JOIN merchant m ON m.id = t.merchant_id
LEFT JOIN merchant_category mc ON mc.id = m.category_id
WHERE t.id = ANY(%(ids)s)
"""

VARIANTS = {
    "char": "character 2-4-gram TF-IDF (word-boundary aware) + one-hot channel/direction/account type",
    "char+word": "character 2-4-gram and word 1-2-gram TF-IDF + one-hot channel/direction/account type",
}


def build_pipeline(variant: str) -> Pipeline:
    text = [("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), min_df=3, max_features=100_000,
                                     sublinear_tf=True, dtype=np.float32), "raw_description")]
    if variant == "char+word":
        text.append(("word", TfidfVectorizer(analyzer="word", ngram_range=(1, 2), min_df=2, max_features=50_000,
                                             token_pattern=r"[A-Z0-9*.]{2,}", lowercase=False,
                                             sublinear_tf=True, dtype=np.float32), "raw_description"))
    features = ColumnTransformer([*text, ("meta", OneHotEncoder(handle_unknown="ignore"),
                                         ["channel", "direction", "account_type"])])
    classifier = SGDClassifier(loss="log_loss", alpha=2e-6, max_iter=30, tol=1e-4, random_state=42, n_jobs=2)
    return Pipeline([("features", features), ("model", classifier)])


def evaluate(y_true: pd.Series, y_pred: np.ndarray, labels: list[str]) -> dict[str, Any]:
    return {
        "macro_f1": float(f1_score(y_true, y_pred, labels=labels, average="macro", zero_division=0)),
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "per_class": classification_report(y_true, y_pred, labels=labels, output_dict=True, zero_division=0),
        "confusion_matrix": {"labels": labels,
                             "matrix": confusion_matrix(y_true, y_pred, labels=labels).tolist()},
    }


def run(activate: bool) -> dict[str, Any]:
    t0 = time.time()
    process = psutil.Process()
    labels = pd.read_parquet(registry.artifacts_dir() / "data" / "transaction_labels.parquet")
    inputs = read_frame(INPUT_SQL, {"ids": labels["transaction_id"].tolist()})
    data = labels.merge(inputs, on="transaction_id", how="inner")
    log.info("labelled rows %d, joined %d, rss %.0f MB", len(labels), len(data), process.memory_info().rss / 2**20)

    months = pd.to_datetime(data["local_date"]).dt.to_period("M")
    last = months.max()
    test_mask = months > last - 6
    val_mask = ~test_mask & (months > last - 9)
    held_out = data["merchant_id"].notna() & (data["merchant_id"].fillna(0).astype("int64") % 5 == 0)
    train = data[~test_mask & ~val_mask & ~held_out]
    val = data[val_mask]
    test = data[test_mask]
    test_held_out = held_out[test_mask].to_numpy()
    category_labels = sorted(data["category"].unique())
    log.info("train %d (%s..%s), validation %d, test %d (%s..%s)", len(train), months[~test_mask & ~val_mask].min(),
             months[~test_mask & ~val_mask].max(), len(val), len(test), months[test_mask].min(), last)

    report: dict[str, Any] = {"baselines": {}, "variants": {}}
    for name, use_mcc in (("rules_with_mcc", True), ("rules_text_only", False)):
        predicted = np.array([rules.classify(r.raw_description, r.channel, r.direction, r.currency, r.account_type,
                                             r.merchant_category if use_mcc else None, bool(r.internal))
                              for r in test.itertuples(index=False)])
        report["baselines"][name] = evaluate(test["category"], predicted, category_labels)
        log.info("baseline %-16s macro F1 %.4f accuracy %.4f", name, report["baselines"][name]["macro_f1"],
                 report["baselines"][name]["accuracy"])

    best = None
    for variant in VARIANTS:
        pipeline = build_pipeline(variant)
        v0 = time.time()
        pipeline.fit(to_frame(train.to_dict("records")), train["category"])
        val_f1 = float(f1_score(val["category"], pipeline.predict(to_frame(val.to_dict("records"))),
                                average="macro"))
        test_pred = pipeline.predict(to_frame(test.to_dict("records")))
        test_eval = evaluate(test["category"], test_pred, category_labels)
        unseen = ~test["raw_description"].isin(set(train["raw_description"])).to_numpy()
        merchant_labels = sorted(test.loc[test_held_out, "category"].unique())
        test_eval["held_out_merchants"] = {
            k: v for k, v in evaluate(test["category"][test_held_out], test_pred[test_held_out],
                                      merchant_labels).items() if k != "confusion_matrix"}
        test_eval["held_out_merchants"]["rows"] = int(test_held_out.sum())
        test_eval["unseen_descriptions"] = {
            "rows": int(unseen.sum()),
            "accuracy": float(accuracy_score(test["category"][unseen], test_pred[unseen])) if unseen.any() else None}
        rss = process.memory_info().rss / 2**20
        report["variants"][variant] = {"description": VARIANTS[variant], "validation_macro_f1": val_f1,
                                       "fit_seconds": round(time.time() - v0, 1), "rss_mb": round(rss), **test_eval}
        log.info("variant %-9s validation macro F1 %.4f test macro F1 %.4f accuracy %.4f | held-out merchants "
                 "macro F1 %.4f (%d rows) | unseen descriptions accuracy %s (%d rows) (%.0f s, rss %.0f MB)",
                 variant, val_f1, test_eval["macro_f1"], test_eval["accuracy"],
                 test_eval["held_out_merchants"]["macro_f1"], test_eval["held_out_merchants"]["rows"],
                 test_eval["unseen_descriptions"]["accuracy"], test_eval["unseen_descriptions"]["rows"],
                 time.time() - v0, rss)
        if best is None or val_f1 > report["variants"][best[0]]["validation_macro_f1"]:
            best = (variant, pipeline)

    assert best is not None
    version = registry.new_version()
    artifact = CategorizerArtifact(version=version, pipeline=best[1], variant=best[0])
    metadata = {
        "name": "nlp",
        "version": version,
        "trained_at": datetime.now(UTC).isoformat(),
        "task": "transaction category from statement text (21 categories)",
        "labels": "stratified sample of generator labels, max 12,000 per category (stand-in for hand labels)",
        "inputs": INPUT_COLUMNS,
        "merchant_hold_out": "merchant_id % 5 == 0 excluded from training",
        "data": {"train": len(train), "validation": len(val), "test": len(test),
                 "test_held_out_merchant_rows": int(test_held_out.sum()),
                 "test_period": f"{months[test_mask].min()}..{last}"},
        "selected_variant": best[0],
        "selection": "highest validation macro F1",
        **report,
        "peak_rss_mb": round(process.memory_info().rss / 2**20),
        "training_seconds": round(time.time() - t0, 1),
    }
    directory = registry.save("nlp", version, artifact, metadata)
    if activate:
        registry.activate("nlp", version)
    log.info("saved %s (%s, variant %s)%s", directory, version, best[0], " and activated" if activate else "")
    summary = {k: {"macro_f1": v["macro_f1"], "accuracy": v["accuracy"]}
               for k, v in {**report["baselines"], **report["variants"]}.items()}
    log.info("summary %s", json.dumps(summary, indent=2))
    return metadata


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--activate", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    run(args.activate)


if __name__ == "__main__":
    main()

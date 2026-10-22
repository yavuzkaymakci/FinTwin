"""Small models trained on synthetic data, so API and scoring tests do not need real artifacts."""

from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import SGDClassifier
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

from app.features import SEGMENTS, from_twin_features
from app.models.nlp_artifact import CategorizerArtifact, to_frame
from app.models.risk_artifact import RiskModelArtifact
from app.scoring.risk_scorer import RiskScorer
from app.training import risk_model


def twin(rng: np.random.Generator) -> dict:
    utilization = float(rng.uniform(0, 1))
    income = float(rng.uniform(10_000, 80_000))
    return {"cardLimit": 20_000, "cardDebt": 20_000 * utilization, "utilizationRate": utilization,
            "incomeEstimate": income, "debtToIncome": float(rng.uniform(0, 0.6)),
            "activeLoans": int(rng.integers(0, 2)),
            "liquidityBufferMonths": float(rng.uniform(0, 12)), "spend90d": 9000.0, "spend30d": 3000.0,
            "spendByCategory90d": {"GROCERY": 4000.0, "CASH_WITHDRAWAL": float(rng.uniform(0, 3000))},
            "avgMonthlyExpense": float(rng.uniform(5_000, 40_000)), "latePaymentCount": int(rng.integers(0, 3)),
            "incomeMonthsObserved": 6, "spendTrend": float(rng.normal(0, 0.2))}


def synthetic_parts(target: str, seed: int) -> dict[str, pd.DataFrame]:
    rng = np.random.default_rng(seed)
    parts = {}
    for name, n in (("train", 3000), ("validation", 1500), ("test", 1500)):
        rows = []
        for i in range(n):
            t = twin(rng)
            segment = SEGMENTS[i % len(SEGMENTS)]
            row = from_twin_features(t, segment)
            driver = t["utilizationRate"] if target == "pd" else 1 - t["activeLoans"] * 0.8
            row.update(customer_id=i, as_of=date(2025, 1, 1), label=int(rng.random() < 0.02 + 0.25 * driver ** 3))
            rows.append(row)
        frame = pd.DataFrame(rows)
        frame["segment"] = pd.Categorical(frame["segment"])
        parts[name] = frame
    return parts


def risk_artifact(target: str) -> RiskModelArtifact:
    artifact, _ = risk_model.train(target, f"test-{target}", synthetic_parts(target, 1 if target == "pd" else 2))
    return artifact


def risk_scorer() -> RiskScorer:
    return RiskScorer(risk_artifact("pd"), risk_artifact("churn"))


def categorizer() -> CategorizerArtifact:
    texts = [("KARDESLER MKT 4123 KADIKOY IST", "POS", "DEBIT", "CHECKING", "GROCERY"),
             ("YILDIZ MARKET SB.12 ANK", "POS", "DEBIT", "CREDIT_CARD", "GROCERY"),
             ("ANADOLU PETROL 1234 CANKAYA ANK", "POS", "DEBIT", "CREDIT_CARD", "FUEL"),
             ("MAVI AKARYKT KEPEZ ANT", "POS", "DEBIT", "CHECKING", "FUEL"),
             ("MAAS ODEMESI ANADOLU GIDA", "EFT_FAST", "CREDIT", "CHECKING", "SALARY"),
             ("SGK EMEKLI AYLIGI 03/2025", "EFT_FAST", "CREDIT", "CHECKING", "SALARY")] * 20
    items = [{"raw_description": d, "channel": c, "direction": di, "account_type": a} for d, c, di, a, _ in texts]
    pipeline = Pipeline([
        ("features", ColumnTransformer([
            ("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4)), "raw_description"),
            ("meta", OneHotEncoder(handle_unknown="ignore"), ["channel", "direction", "account_type"])])),
        ("model", SGDClassifier(loss="log_loss", random_state=1))])
    pipeline.fit(to_frame(items), [t[4] for t in texts])
    return CategorizerArtifact(version="test-nlp", pipeline=pipeline, variant="char")

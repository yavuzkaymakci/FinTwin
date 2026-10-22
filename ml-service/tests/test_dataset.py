from __future__ import annotations

import json
from datetime import date

import pandas as pd

from app.features import from_twin_features
from app.training.dataset import RawData, add_months, build_dataset
from app.training.splits import time_split

CARD = {"cardLimit": 10000, "cardDebt": 2500, "utilizationRate": 0.25, "incomeEstimate": 20000,
        "debtToIncome": 0.1, "activeLoans": 0, "spend90d": 9000, "spendByCategory90d": {"GROCERY": 3000},
        "latePaymentCount": 0, "incomeMonthsObserved": 3}
NO_CREDIT = {"cardLimit": 0, "cardDebt": 0, "activeLoans": 0, "incomeEstimate": 15000}


def snapshots(*rows: tuple[int, date, dict]) -> pd.DataFrame:
    return pd.DataFrame([{"customer_id": c, "as_of": d, "last_seq": i + 1, "features": json.dumps(f),
                          "segment": "WHITE_COLLAR"} for i, (c, d, f) in enumerate(rows)])


def events(*rows: tuple[int, str, date]) -> pd.DataFrame:
    return pd.DataFrame(rows, columns=["customer_id", "event_type", "event_date"])


def months(start: date, n: int) -> list[date]:
    return [add_months(start, i) for i in range(n)]


def test_label_window_is_six_months_after_the_observation_month() -> None:
    raw = RawData(snapshots((1, date(2025, 1, 1), CARD), (1, date(2025, 2, 1), CARD), (1, date(2025, 7, 1), CARD)),
                  events((1, "DEFAULT", date(2025, 7, 15))), date(2026, 10, 1))
    labels = build_dataset(raw, "pd").set_index("as_of")["label"].to_dict()
    # 2025-01: window [01-01, 07-01) ends before the default; 2025-02: [02-01, 08-01) contains it.
    assert labels == {date(2025, 1, 1): 0, date(2025, 2, 1): 1, date(2025, 7, 1): 1}


def test_window_end_is_exclusive_and_start_inclusive() -> None:
    raw = RawData(snapshots((1, date(2025, 1, 1), CARD), (2, date(2025, 1, 1), CARD)),
                  events((1, "DEFAULT", date(2025, 7, 1)), (2, "DEFAULT", date(2025, 1, 1))), date(2026, 10, 1))
    labels = build_dataset(raw, "pd").set_index("customer_id")["label"].to_dict()
    assert labels == {1: 0, 2: 1}


def test_incomplete_label_windows_are_excluded() -> None:
    raw = RawData(snapshots(*[(1, d, CARD) for d in months(date(2026, 1, 1), 9)]), events(), date(2026, 10, 1))
    frame = build_dataset(raw, "churn")
    assert frame["as_of"].max() == date(2026, 4, 1)
    assert len(frame) == 4


def test_customers_that_already_had_the_event_are_excluded() -> None:
    raw = RawData(snapshots((1, date(2025, 3, 1), CARD)), events((1, "CHURN", date(2025, 2, 10))),
                  date(2026, 10, 1))
    assert build_dataset(raw, "churn").empty


def test_pd_population_requires_credit_exposure() -> None:
    raw = RawData(snapshots((1, date(2025, 1, 1), CARD), (2, date(2025, 1, 1), NO_CREDIT)), events(),
                  date(2026, 10, 1))
    assert build_dataset(raw, "pd")["customer_id"].tolist() == [1]
    assert sorted(build_dataset(raw, "churn")["customer_id"].tolist()) == [1, 2]


def test_features_are_point_in_time() -> None:
    """Anything that happens after the observation month cannot change that month's features."""
    base = RawData(snapshots((1, date(2025, 1, 1), CARD)), events(), date(2026, 10, 1))
    later_card = {**CARD, "utilizationRate": 0.99, "latePaymentCount": 7}
    future = RawData(snapshots((1, date(2025, 1, 1), CARD), (1, date(2025, 6, 1), later_card)),
                     events((1, "DEFAULT", date(2025, 9, 1)), (1, "CHURN", date(2026, 1, 1))), date(2026, 10, 1))
    before = build_dataset(base, "pd").drop(columns=["label"]).iloc[0]
    after = build_dataset(future, "pd").drop(columns=["label"]).set_index("as_of").loc[date(2025, 1, 1)]
    assert before.drop("as_of").equals(after.reindex(before.drop("as_of").index))
    assert build_dataset(base, "pd").iloc[0].to_dict()["utilization_rate"] == \
        from_twin_features(CARD, "WHITE_COLLAR")["utilization_rate"]


def test_time_split_orders_periods() -> None:
    frame = pd.DataFrame({"as_of": months(date(2024, 11, 1), 18), "label": 0})
    parts, periods = time_split(frame)
    assert [p.name for p in periods] == ["train", "validation", "test"]
    assert len(parts["train"]) == 8 and len(parts["validation"]) == 4 and len(parts["test"]) == 6
    assert periods[0].end < periods[1].start and periods[1].end < periods[2].start

"""Point-in-time checks against the real twin data (needs ``make up`` with replayed data)."""

from __future__ import annotations

import json
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.db import read_frame
from app.training.dataset import add_months, build_dataset, load_raw

pytestmark = pytest.mark.usefixtures("database")


def test_no_snapshot_contains_events_at_or_after_its_observation_date() -> None:
    leaks = read_frame("""
        SELECT COUNT(*) AS n FROM twin.customer_twin_snapshot s
        JOIN twin.event_store e ON e.customer_id = s.customer_id AND e.customer_seq = s.last_seq
        WHERE e.occurred_at >= (s.as_of::timestamp AT TIME ZONE 'Europe/Istanbul')""")
    # customer_seq follows event time, so the event with the snapshot's last sequence is its latest event.
    applied = read_frame("""
        SELECT COUNT(*) AS n FROM twin.customer_twin_snapshot s
        JOIN twin.event_store e ON e.customer_id = s.customer_id AND e.customer_seq = s.last_seq""")
    total = read_frame("SELECT COUNT(*) AS n FROM twin.customer_twin_snapshot")
    assert int(applied.iloc[0]["n"]) == int(total.iloc[0]["n"])
    assert int(leaks.iloc[0]["n"]) == 0


def test_snapshot_features_are_evaluated_at_the_observation_date() -> None:
    rows = read_frame("SELECT as_of, features::text AS features FROM twin.customer_twin_snapshot "
                      "ORDER BY customer_id, as_of LIMIT 2000")
    zone = ZoneInfo("Europe/Istanbul")
    for as_of, features in rows.itertuples(index=False):
        evaluated = datetime.fromisoformat(json.loads(features)["evaluatedAt"].replace("Z", "+00:00"))
        assert evaluated == datetime(as_of.year, as_of.month, as_of.day, tzinfo=zone)


def test_training_rows_have_complete_label_windows() -> None:
    raw = load_raw()
    frame = build_dataset(raw, "churn")
    assert not frame.empty
    assert all(add_months(d, 6) <= raw.data_end for d in frame["as_of"].unique())

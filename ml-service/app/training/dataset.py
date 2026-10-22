"""Training data for the PD and churn models: one row per customer and observation month.

Features come from the twin's month-start snapshots (``twin.customer_twin_snapshot``): a snapshot ``as_of = M-01``
holds the state built from events that happened before M-01 00:00 bank time, so features are point-in-time by
construction. Labels come from the core banking event table (``public.customer_event``): the target event within
``[as_of, as_of + horizon)``. Observation months whose label window is not yet complete are dropped.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date

import pandas as pd

from app.db import read_frame
from app.features import FEATURES, from_twin_features, has_credit_exposure

HORIZON_MONTHS = 6

TARGET_EVENTS = {"pd": "DEFAULT", "churn": "CHURN"}

SNAPSHOT_SQL = """
SELECT s.customer_id, s.as_of, s.last_seq, s.features::text AS features, s.state ->> 'segment' AS segment
FROM twin.customer_twin_snapshot s
ORDER BY s.customer_id, s.as_of
"""

EVENT_SQL = """
SELECT customer_id, event_type, event_date FROM customer_event WHERE event_type IN ('DEFAULT', 'CHURN')
"""

# End of the observed history: the month after the last event the twin processed (bank time).
DATA_END_SQL = """
SELECT (date_trunc('month', MAX(occurred_at) AT TIME ZONE 'Europe/Istanbul') + INTERVAL '1 month')::date
FROM twin.event_store
"""


@dataclass(frozen=True)
class RawData:
    snapshots: pd.DataFrame
    events: pd.DataFrame
    data_end: date


def load_raw(url: str | None = None) -> RawData:
    snapshots = read_frame(SNAPSHOT_SQL, url=url)
    events = read_frame(EVENT_SQL, url=url)
    data_end = read_frame(DATA_END_SQL, url=url).iloc[0, 0]
    return RawData(snapshots, events, data_end)


def add_months(d: date, months: int) -> date:
    month = d.month - 1 + months
    return date(d.year + month // 12, month % 12 + 1, 1)


def build_dataset(raw: RawData, target: str, horizon_months: int = HORIZON_MONTHS) -> pd.DataFrame:
    """Rows ``customer_id, as_of, segment, <features>, label`` for the target (``pd`` or ``churn``).

    Pure function of its inputs: features only use the snapshot row, labels only events in the window after it.
    """
    event_type = TARGET_EVENTS[target]
    events = raw.events[raw.events["event_type"] == event_type]
    first_event = events.groupby("customer_id")["event_date"].min().to_dict()

    rows = []
    for snap in raw.snapshots.itertuples(index=False):
        as_of: date = snap.as_of
        window_end = add_months(as_of, horizon_months)
        if window_end > raw.data_end:
            continue  # label window not complete yet
        event_date = first_event.get(snap.customer_id)
        if event_date is not None and event_date < as_of:
            continue  # already defaulted / churned before the observation date
        twin = json.loads(snap.features) if isinstance(snap.features, str) else snap.features
        if target == "pd" and not has_credit_exposure(twin):
            continue
        row = from_twin_features(twin, snap.segment)
        row["customer_id"] = snap.customer_id
        row["as_of"] = as_of
        row["label"] = int(event_date is not None and as_of <= event_date < window_end)
        rows.append(row)
    frame = pd.DataFrame(rows, columns=["customer_id", "as_of", *FEATURES, "label"])
    frame["segment"] = pd.Categorical(frame["segment"])
    return frame

"""Simulation start state and scenarios from the local database (skipped without ``make up`` and data)."""

from __future__ import annotations

from datetime import date

import pytest

from app.db import read_frame
from app.simulation.macro import load_macro_start
from app.simulation.scenario import ScenarioParameters
from app.simulation.start_state import ENDED_SQL, load_start

START = date(2025, 9, 1)


def test_start_state_comes_from_the_snapshot_and_excludes_ended_customers(database: None) -> None:
    start = load_start(START)
    ended = set(read_frame(ENDED_SQL, (START,))["customer_id"])
    assert start.size > 1000
    assert not ended & set(start.customer_id.tolist())
    snapshot_ids = set(read_frame("SELECT customer_id FROM twin.customer_twin_snapshot WHERE as_of = %s",
                                  (START,))["customer_id"])
    assert set(start.customer_id.tolist()) <= snapshot_ids
    assert start.credit.mean() > 0.5
    assert (start.loan_remaining >= 0).all() and (start.income_history >= 0).all()


def test_macro_start_uses_the_month_before(database: None) -> None:
    macro = load_macro_start(START)
    august = read_frame("SELECT value::float8 AS v FROM macro_indicator WHERE indicator = 'USD_TRY' "
                        "AND period = '2025-08-01'")["v"].iloc[0]
    assert macro.usd_try == pytest.approx(august)
    assert 0 < macro.policy_rate < 1
    assert date(2025, 8, 1) in macro.inflation_history


def test_preset_scenarios_are_valid(database: None) -> None:
    presets = read_frame("SELECT code, parameters::text AS p FROM simulation.scenario WHERE preset")
    assert set(presets["code"]) == {"BASE", "FX_SHOCK", "RATE_SHOCK", "STAGFLATION", "RECOVERY"}
    for row in presets.itertuples(index=False):
        ScenarioParameters.model_validate_json(row.p)

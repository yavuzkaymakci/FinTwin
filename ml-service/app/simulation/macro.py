"""Macro paths: from a scenario and the macro history to monthly prices, rates, USD/TRY and income indices.

Index 0 of every array is the starting point (end of the month before the first simulated month); index ``t`` is
simulated month ``t`` (1-based). Values come from ``macro_indicator`` (monthly CPI inflation in %, annual policy
rate in %, monthly average USD/TRY).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

import numpy as np

from app.db import read_frame
from app.features import SEGMENTS
from app.simulation.scenario import ScenarioParameters, ShockProfile

MACRO_SQL = "SELECT indicator, period, value::float8 AS value FROM macro_indicator ORDER BY period"


def add_months(d: date, months: int) -> date:
    month = d.month - 1 + months
    return date(d.year + month // 12, month % 12 + 1, 1)


def card_monthly_rate(policy_rate: np.ndarray | float) -> np.ndarray:
    """Contractual monthly card rate: tracks the policy rate within the regulatory band (as in the generator)."""
    return np.clip(np.asarray(policy_rate) / 12 * 0.95 + 0.0025, 0.0175, 0.05)


def deposit_monthly_rate(policy_rate: np.ndarray | float) -> np.ndarray:
    """Gross monthly time-deposit rate: policy rate minus 4 percentage points (as in the generator)."""
    return np.maximum(np.asarray(policy_rate) - 0.04, 0.0) / 12


@dataclass(frozen=True)
class MacroStart:
    """Macro state at the start of the simulation."""

    start: date  # first simulated month (month start)
    usd_try: float  # USD/TRY of the month before the start
    policy_rate: float  # annual policy rate (fraction) of the month before the start
    #: Realised monthly inflation (fraction) by month, used for the wage catch-up of raises after the start.
    inflation_history: dict[date, float] = field(default_factory=dict)
    #: Realised USD/TRY and policy rate by month (back-tests).
    usd_try_history: dict[date, float] = field(default_factory=dict)
    policy_history: dict[date, float] = field(default_factory=dict)


@dataclass(frozen=True)
class MacroPath:
    months: list[date]
    inflation: np.ndarray  # (H,) monthly CPI inflation of month t
    price_index: np.ndarray  # (H+1,) CPI level, 1 at the start
    usd_try: np.ndarray  # (H+1,)
    policy_rate: np.ndarray  # (H+1,) annual
    card_rate: np.ndarray  # (H+1,) monthly
    deposit_rate: np.ndarray  # (H+1,) monthly, gross
    wage_index: dict[str, np.ndarray]  # segment -> (H+1,) nominal income level relative to the start

    @property
    def horizon(self) -> int:
        return len(self.months)

    def as_rows(self) -> list[dict[str, float | str]]:
        rows = []
        for t, month in enumerate(self.months, start=1):
            rows.append({"month": month.isoformat()[:7], "inflationPct": round(float(self.inflation[t - 1]) * 100, 3),
                         "priceIndex": round(float(self.price_index[t]), 5),
                         "usdTry": round(float(self.usd_try[t]), 4),
                         "policyRatePct": round(float(self.policy_rate[t]) * 100, 3),
                         "cardRatePct": round(float(self.card_rate[t]) * 100, 3),
                         "wageIndex": {s: round(float(w[t]), 5) for s, w in self.wage_index.items()}})
        return rows


def is_raise_month(month: date, lag: int) -> bool:
    return lag <= 1 or (month.month - 1) % lag == 0


def inflation_since_last_raise(start: date, lag: int, history: dict[date, float]) -> float:
    """Inflation accumulated between the last raise before ``start`` and the start (missing months count as 0)."""
    last = add_months(start, -1)
    if lag > 1:
        while not is_raise_month(last, lag):
            last = add_months(last, -1)
    level = 1.0
    month = last
    while month < start:
        level *= 1 + history.get(month, 0.0)
        month = add_months(month, 1)
    return level - 1


def build_path(scenario: ScenarioParameters, start: MacroStart) -> MacroPath:
    horizon = scenario.horizon_months
    months = [add_months(start.start, t) for t in range(horizon)]
    inflation = np.array([scenario.inflation(t) for t in range(1, horizon + 1)])
    price = np.concatenate([[1.0], np.cumprod(1 + inflation)])
    ramp = np.array([0.0, *(scenario.ramp(t) for t in range(1, horizon + 1))])
    usd = start.usd_try * (1 + scenario.usd_try_change_pct / 100 * ramp)
    policy = np.maximum(start.policy_rate + scenario.policy_rate_change_bp / 10_000 * ramp, 0.0)

    wage_index = {}
    for segment in SEGMENTS:
        share, lag = scenario.wage_rule(segment)
        index = np.ones(horizon + 1)
        reference = 1 / (1 + inflation_since_last_raise(start.start, lag, start.inflation_history))
        for t in range(1, horizon + 1):
            index[t] = index[t - 1]
            if is_raise_month(months[t - 1], lag):
                index[t] *= 1 + share * (price[t - 1] / reference - 1)
                reference = price[t - 1]
        wage_index[segment] = index
    return MacroPath(months, inflation, price, usd, policy, card_monthly_rate(policy), deposit_monthly_rate(policy),
                     wage_index)


def load_macro_start(start: date, url: str | None = None) -> MacroStart:
    """Starting values and history from ``macro_indicator`` (the month before ``start`` or the latest before it)."""
    frame = read_frame(MACRO_SQL, url=url)
    series: dict[str, dict[date, float]] = {}
    for row in frame.itertuples(index=False):
        series.setdefault(row.indicator, {})[row.period] = float(row.value)

    def latest_before(name: str) -> float:
        values = series.get(name, {})
        before = [d for d in values if d < start]
        if not before:
            raise ValueError(f"No {name} value before {start}")
        return values[max(before)]

    return MacroStart(
        start=start,
        usd_try=latest_before("USD_TRY"),
        policy_rate=latest_before("POLICY_RATE") / 100,
        inflation_history={d: v / 100 for d, v in series.get("MONTHLY_INFLATION", {}).items()},
        usd_try_history=series.get("USD_TRY", {}),
        policy_history={d: v / 100 for d, v in series.get("POLICY_RATE", {}).items()})


def reference_path(start: MacroStart, horizon: int) -> MacroPath:
    """Neutral continuation the scenario is measured against: inflation at its trailing 12-month average, incomes
    raised in January and July by the full past inflation (the dominant practice in the training period), USD/TRY
    moving with prices (constant real rate) and an unchanged policy rate."""
    months = [add_months(start.start, -m) for m in range(1, 13)]
    level = float(np.prod([1 + start.inflation_history.get(m, 0.0) for m in months]))
    monthly = level ** (1 / 12) - 1
    # model_construct: internal path, may exceed the 36-month limit of user scenarios (savings goals).
    neutral = ScenarioParameters.model_construct(
        horizon_months=horizon, usd_try_change_pct=min(((1 + monthly) ** horizon - 1) * 100, 200),
        policy_rate_change_bp=0, monthly_inflation_pct=[monthly * 100], wage_indexation_pct=100,
        wage_adjustment_lag_months=6, shock_profile=ShockProfile.GRADUAL, shock_ramp_months=horizon,
        monte_carlo_paths=10)
    return build_path(neutral, start)

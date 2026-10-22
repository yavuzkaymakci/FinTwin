"""Starting point of a simulation: the twins' state at a month start (snapshot) or now (projection).

Only the twin schema and the core banking event table are read (role ``fintwin_ml``): customers who already
defaulted or churned before the start are excluded. Money is converted to TRY (floats); the twin keeps minor units.
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import date
from typing import Any

import numpy as np

from app.db import read_frame
from app.features import SEGMENTS, from_twin_features, has_credit_exposure
from app.simulation.macro import add_months

#: Spending categories tracked by the projection (twin categories; anything else is folded into OTHER).
ESSENTIAL = ["GROCERY", "UTILITIES", "TELECOM", "RENT", "HEALTH", "TRANSPORT", "FUEL", "EDUCATION",
             "CASH_WITHDRAWAL", "OTHER"]
DISCRETIONARY = ["RESTAURANT", "ENTERTAINMENT", "TRAVEL", "CLOTHING", "ELECTRONICS", "ECOMMERCE"]
CATEGORIES = ESSENTIAL + DISCRETIONARY
FX_SENSITIVE = ["ELECTRONICS", "TRAVEL"]
CATEGORY_INDEX = {c: i for i, c in enumerate(CATEGORIES)}

CURRENT_SQL = "SELECT customer_id, state::text AS state, features::text AS features FROM twin.customer_twin_state"
SNAPSHOT_SQL = """
SELECT customer_id, state::text AS state, features::text AS features
FROM twin.customer_twin_snapshot WHERE as_of = %s
"""
ENDED_SQL = """
SELECT DISTINCT customer_id FROM customer_event WHERE event_type IN ('DEFAULT', 'CHURN') AND event_date < %s
"""
# The month after the last event the twin processed (bank time): the start of a projection from the current state.
DATA_END_SQL = """
SELECT (date_trunc('month', MAX(occurred_at) AT TIME ZONE 'Europe/Istanbul') + INTERVAL '1 month')::date
FROM twin.event_store
"""


@dataclass
class PortfolioStart:
    """Column arrays of the starting portfolio (one entry per customer)."""

    start: date
    customer_id: np.ndarray  # (C,) int64
    segment: list[str]
    segment_index: np.ndarray  # (C,) int, index into SEGMENTS (-1 = unknown)
    credit: np.ndarray  # (C,) bool: credit exposure (PD population)
    features0: list[dict[str, Any]]  # model features at the start (exactly as the scoring API computes them)
    income_history: np.ndarray  # (C, 3) monthly income of the last 3 complete months
    has_income: np.ndarray  # (C,) bool
    category_base: np.ndarray  # (C, K) monthly spending per category (90-day average)
    spend30: np.ndarray  # (C,)
    spend90: np.ndarray  # (C,)
    expense0: np.ndarray  # (C,) average monthly external outflow
    expense_ratio0: np.ndarray  # (C,) expense0 / income (NaN without income)
    expense_ratio_target: np.ndarray  # (C,) mean expense-to-income ratio of the customer's segment at the start
    liquidity0: np.ndarray  # (C,) TRY checking + time deposits
    deposit_share0: np.ndarray  # (C,) deposits / liquidity (NaN without liquidity)
    usd0: np.ndarray  # (C,) USD balance in USD
    card_debt0: np.ndarray
    card_limit0: np.ndarray
    minimum_ratio: np.ndarray  # (C,) card minimum payment / card balance
    loan_installment: np.ndarray  # (C, NL)
    loan_outstanding: np.ndarray  # (C, NL)
    loan_remaining: np.ndarray  # (C, NL) int
    loan_mortgage: np.ndarray  # (C, NL) bool
    late_count0: np.ndarray
    days_since_late0: np.ndarray  # NEVER_LATE_DAYS if never late
    income_cv: np.ndarray
    salary_day_deviation: np.ndarray
    no_income_observed: np.ndarray
    has_card: np.ndarray
    usd_holder: np.ndarray

    @property
    def size(self) -> int:
        return len(self.customer_id)

    def subset(self, index: np.ndarray) -> PortfolioStart:
        values = {}
        for name, value in self.__dict__.items():
            if isinstance(value, np.ndarray):
                values[name] = value[index]
            elif isinstance(value, list):
                values[name] = [value[i] for i in index]
            else:
                values[name] = value
        return PortfolioStart(**values)


def _num(value: Any, default: float = np.nan) -> float:
    try:
        f = float(value)
    except (TypeError, ValueError):
        return default
    return f if np.isfinite(f) else default


def _cents(value: Any) -> float:
    return _num(value, 0.0) / 100


def from_rows(start: date, rows: Iterable[tuple[int, Mapping[str, Any], Mapping[str, Any]]]) -> PortfolioStart:
    """Builds the starting portfolio from (customer_id, twin state JSON, twin features JSON) rows."""
    rows = sorted(rows, key=lambda r: r[0])
    n = len(rows)
    max_loans = max([sum(1 for loan in (s.get("loans") or {}).values() if _active(loan)) for _, s, _ in rows] or [0])
    max_loans = max(max_loans, 1)
    k = len(CATEGORIES)
    out: dict[str, Any] = {
        "income_history": np.zeros((n, 3)), "category_base": np.zeros((n, k)),
        "loan_installment": np.zeros((n, max_loans)), "loan_outstanding": np.zeros((n, max_loans)),
        "loan_remaining": np.zeros((n, max_loans), dtype=np.int64),
        "loan_mortgage": np.zeros((n, max_loans), dtype=bool)}
    scalars = {name: np.zeros(n) for name in (
        "spend30", "spend90", "expense0", "liquidity0", "deposit_share0", "usd0", "card_debt0", "card_limit0",
        "minimum_ratio", "late_count0", "days_since_late0", "income_cv", "salary_day_deviation",
        "no_income_observed", "has_card", "usd_holder")}
    ids = np.zeros(n, dtype=np.int64)
    segments: list[str] = []
    credit = np.zeros(n, dtype=bool)
    has_income = np.zeros(n, dtype=bool)
    features0 = []
    history_months = [add_months(start, -m).isoformat()[:7] for m in (3, 2, 1)]

    for i, (customer_id, state, twin) in enumerate(rows):
        ids[i] = customer_id
        segment = state.get("segment")
        segments.append(segment if segment in SEGMENTS else "")
        model_features = from_twin_features(twin, segment)
        features0.append(model_features)
        credit[i] = has_credit_exposure(twin)

        income = _num(twin.get("incomeEstimate"))
        has_income[i] = np.isfinite(income) and income > 0
        monthly = state.get("monthlyIncome") or {}
        for j, month in enumerate(history_months):
            value = monthly.get(month)
            out["income_history"][i, j] = _cents(value) if value is not None else (income if has_income[i] else 0)
        if not has_income[i]:
            out["income_history"][i] = 0

        categories = twin.get("spendByCategory90d") or {}
        for name, amount in categories.items():
            out["category_base"][i, CATEGORY_INDEX.get(name, CATEGORY_INDEX["OTHER"])] += _num(amount, 0) / 3
        spend90 = _num(twin.get("spend90d"), 0.0)
        scalars["spend90"][i] = spend90
        scalars["spend30"][i] = _num(twin.get("spend30d"), 0.0)

        loans = [loan for loan in (state.get("loans") or {}).values() if _active(loan)]
        for j, loan in enumerate(loans):
            out["loan_installment"][i, j] = _cents(loan.get("installmentCents"))
            out["loan_outstanding"][i, j] = _cents(loan.get("outstandingCents"))
            out["loan_remaining"][i, j] = int(loan.get("remainingInstallments") or 0)
            out["loan_mortgage"][i, j] = loan.get("type") == "MORTGAGE"
        installments = out["loan_installment"][i].sum()

        expense = _num(twin.get("avgMonthlyExpense"))
        scalars["expense0"][i] = expense if np.isfinite(expense) else spend90 / 3 + installments
        checking = _num(twin.get("checkingBalance"), 0.0)
        deposit = _num(twin.get("depositBalance"), 0.0)
        liquidity = checking + deposit
        scalars["liquidity0"][i] = liquidity
        scalars["deposit_share0"][i] = deposit / liquidity if liquidity > 0 else np.nan
        scalars["usd0"][i] = max(_num(twin.get("usdBalance"), 0.0), 0.0)
        card_debt = max(_num(twin.get("cardDebt"), 0.0), 0.0)
        scalars["card_debt0"][i] = card_debt
        scalars["card_limit0"][i] = _num(twin.get("cardLimit"), 0.0)
        statement = state.get("lastStatement") or {}
        balance = _cents(statement.get("balanceCents"))
        minimum = _cents(statement.get("minimumCents"))
        scalars["minimum_ratio"][i] = min(minimum / balance, 1.0) if balance > 0 else 0.2

        scalars["late_count0"][i] = model_features["late_payment_count"]
        scalars["days_since_late0"][i] = model_features["days_since_last_late"]
        scalars["income_cv"][i] = _num(model_features["income_cv"])
        scalars["salary_day_deviation"][i] = _num(model_features["salary_day_deviation"])
        scalars["no_income_observed"][i] = model_features["no_income_observed"]
        scalars["has_card"][i] = model_features["has_card"]
        scalars["usd_holder"][i] = model_features["usd_holder"]

    income_level = np.median(out["income_history"], axis=1)
    ratio = np.where(income_level > 0, scalars["expense0"] / np.where(income_level > 0, income_level, 1), np.nan)
    segment_index = np.array([SEGMENTS.index(s) if s in SEGMENTS else -1 for s in segments], dtype=np.int64)
    target = np.full(n, np.nan)
    for k in np.unique(segment_index):
        members = (segment_index == k) & np.isfinite(ratio)
        if members.any():
            target[segment_index == k] = np.clip(ratio[members], 0, 3).mean()
    return PortfolioStart(
        start=start, customer_id=ids, expense_ratio0=ratio, expense_ratio_target=target, segment=segments,
        segment_index=segment_index,
        credit=credit, features0=features0, has_income=has_income, **out, **scalars)


def _active(loan: Mapping[str, Any]) -> bool:
    return bool(loan.get("active", True)) and int(loan.get("remainingInstallments") or 0) > 0


def _open(state: Mapping[str, Any]) -> bool:
    accounts = state.get("accounts") or {}
    return not accounts or any(not a.get("closed", False) for a in accounts.values())


def data_end(url: str | None = None) -> date:
    return read_frame(DATA_END_SQL, url=url).iloc[0, 0]


def load_start(start_date: date | None, customer_ids: list[int] | None = None,
               url: str | None = None) -> PortfolioStart:
    """Loads the starting portfolio: the snapshot at ``start_date`` or the current twin state."""
    if start_date is None:
        start = data_end(url)
        frame = read_frame(CURRENT_SQL + (" WHERE customer_id = ANY(%s)" if customer_ids else ""),
                           (list(customer_ids),) if customer_ids else None, url=url)
    else:
        start = start_date
        frame = read_frame(SNAPSHOT_SQL + (" AND customer_id = ANY(%s)" if customer_ids else ""),
                           (start_date, list(customer_ids)) if customer_ids else (start_date,), url=url)
        if frame.empty:
            raise ValueError(f"No twin snapshots at {start_date}")
    ended = set(read_frame(ENDED_SQL, (start,), url=url)["customer_id"].tolist()) if not frame.empty else set()
    wanted = set(customer_ids) if customer_ids else None
    rows = []
    for row in frame.itertuples(index=False):
        if row.customer_id in ended or (wanted is not None and row.customer_id not in wanted):
            continue
        state = json.loads(row.state)
        if _open(state):
            rows.append((int(row.customer_id), state, json.loads(row.features)))
    if not rows:
        raise ValueError("No active customers to simulate")
    return from_rows(start, rows)


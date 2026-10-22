"""Synthetic starting portfolios and macro history for simulation tests (no database needed)."""

from __future__ import annotations

from datetime import date
from typing import Any

import numpy as np

from app.features import SEGMENTS
from app.simulation.macro import MacroStart, add_months
from app.simulation.scenario import ScenarioParameters
from app.simulation.start_state import PortfolioStart, from_rows

START = date(2025, 9, 1)


def macro_start(start: date = START) -> MacroStart:
    history = {add_months(start, -m): 0.02 for m in range(1, 25)}
    return MacroStart(start=start, usd_try=40.0, policy_rate=0.40, inflation_history=history)


def customer(rng: np.random.Generator, customer_id: int, usd: float = 0.0, stressed: bool | None = None
             ) -> tuple[int, dict[str, Any], dict[str, Any]]:
    """A twin (state, features) pair; ``stressed`` customers spend about their income and hold little cash."""
    stressed = bool(rng.random() < 0.5) if stressed is None else stressed
    income = float(rng.uniform(20_000, 60_000))
    expense_ratio = float(rng.uniform(0.95, 1.1) if stressed else rng.uniform(0.6, 0.85))
    expense = income * expense_ratio
    limit = float(rng.choice([20_000, 40_000, 80_000]))
    debt = limit * float(rng.uniform(0.3, 0.8) if stressed else rng.uniform(0.0, 0.4))
    liquidity = expense * float(rng.uniform(0.1, 0.8) if stressed else rng.uniform(2, 8))
    spend = expense * 0.8
    categories = {"GROCERY": spend * 0.35, "UTILITIES": spend * 0.1, "RENT": spend * 0.2,
                  "RESTAURANT": spend * 0.12, "ELECTRONICS": spend * 0.08, "TRAVEL": spend * 0.05,
                  "CASH_WITHDRAWAL": spend * 0.1}
    has_loan = bool(rng.random() < 0.4)
    installment = income * 0.15 if has_loan else 0.0
    loans = {"1": {"type": "PERSONAL", "active": True, "installmentCents": round(installment * 100),
                   "outstandingCents": round(installment * 10 * 100), "remainingInstallments": 10}} if has_loan else {}
    minimum = debt * 0.2
    months = {add_months(START, -m).isoformat()[:7]: round(income * 100) for m in range(1, 7)}
    state = {"segment": SEGMENTS[customer_id % len(SEGMENTS)], "loans": loans, "monthlyIncome": months,
             "lastStatement": {"balanceCents": round(debt * 100), "minimumCents": round(minimum * 100)},
             "accounts": {"1": {"closed": False}}}
    features = {
        "incomeEstimate": income, "incomeCv": float(rng.uniform(0.02, 0.2)), "salaryDayDeviation": 0.0,
        "incomeMonthsObserved": 6, "checkingBalance": liquidity * 0.7, "depositBalance": liquidity * 0.3,
        "usdBalance": usd, "cardDebt": debt, "cardLimit": limit, "utilizationRate": debt / limit,
        "loanOutstanding": installment * 10, "totalDebt": debt + installment * 10, "activeLoans": int(has_loan),
        "spend30d": spend * 3 * 0.34, "spend90d": spend * 3, "spendByCategory90d": {k: v * 3 for k, v in
                                                                                  categories.items()},
        "spendTrend": 0.02, "avgMonthlyExpense": expense, "monthlyObligations": installment + minimum,
        "debtToIncome": (installment + minimum) / income, "latePaymentCount": 0, "daysSinceLastLatePayment": None,
        "liquidityBufferMonths": liquidity / expense}
    return customer_id, state, features


def portfolio(n: int = 200, seed: int = 3, usd: float = 0.0, stressed: bool | None = None,
              first_id: int = 1) -> PortfolioStart:
    rng = np.random.default_rng(seed)
    return from_rows(START, [customer(rng, first_id + i, usd, stressed) for i in range(n)])


def scenario(**overrides: Any) -> ScenarioParameters:
    values: dict[str, Any] = {"horizon_months": 6, "monthly_inflation_pct": [2.0], "wage_indexation_pct": 100,
                              "wage_adjustment_lag_months": 6, "monte_carlo_paths": 40, "seed": 7}
    values.update(overrides)
    return ScenarioParameters(**values)

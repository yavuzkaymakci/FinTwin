"""Savings-goal simulation for one customer: will the liquid balance reach a target within a number of months?

The customer's current twin is projected with the simulation engine on the neutral macro path (inflation at its
trailing average, incomes indexed every six months), with Monte Carlo income and spending shocks. Optional
changes are a fixed extra monthly saving and spending cuts per category. The result is the probability of
reaching the target with and without the changes, and the extra monthly saving needed for a given confidence.
Amounts are nominal TRY; the expected price level at the horizon is reported so the target can be read in today's
prices.
"""

from __future__ import annotations

from typing import Any

import numpy as np
from pydantic import Field, field_validator

from app.simulation.engine import Draws, Projection, systematic_factor
from app.simulation.macro import MacroStart, reference_path
from app.simulation.scenario import Assumptions, SchemaModel
from app.simulation.start_state import CATEGORIES, CATEGORY_INDEX, PortfolioStart

CUTTABLE = [c for c in CATEGORIES if c != "OTHER"]


class CategoryCut(SchemaModel):
    category: str = Field(description=f"Spending category, one of {', '.join(CUTTABLE)}")
    cut_pct: float = Field(ge=0, le=100, description="Reduction of the category's spending in percent (50 = half)")

    @field_validator("category")
    @classmethod
    def known(cls, value: str) -> str:
        if value not in CUTTABLE:
            raise ValueError(f"unknown category {value!r}; expected one of {CUTTABLE}")
        return value


class GoalRequest(SchemaModel):
    customer_id: int
    target_amount: float = Field(gt=0, le=1e9, description="Savings target in nominal TRY")
    months: int = Field(ge=1, le=120, description="Months to reach the target")
    extra_monthly_saving: float = Field(0, ge=0, le=1e7, description="Additional saving every month, TRY")
    category_cuts: list[CategoryCut] = Field(default_factory=list, max_length=len(CUTTABLE))
    confidence: float = Field(0.8, ge=0.5, le=0.99, description="Probability the required saving should reach")
    paths: int = Field(1000, ge=100, le=5000)
    seed: int = Field(42, ge=0, le=2_147_483_647)


def _balances(start: PortfolioStart, request: GoalRequest, macro: MacroStart, scale: np.ndarray, saving: float,
              assumptions: Assumptions) -> np.ndarray:
    """Liquid balance (TRY + USD) per path and month, (H, P), with common random numbers."""
    path = reference_path(macro, request.months)
    draws = Draws.for_customers(request.seed, start.customer_id, request.months, request.paths)
    systematic = systematic_factor(request.seed, request.months, request.paths, assumptions)
    projection = Projection(start, path, assumptions, request.paths, category_scale=scale, extra_saving=saving)
    out = np.empty((request.months, request.paths))
    for t in range(request.months):
        projection.step(systematic[t], draws.income[t], draws.expense[t])
        out[t] = projection.balances()[0][:, 0]
    return out


def _band(values: np.ndarray) -> dict[str, float]:
    p10, p50, p90 = np.percentile(values, [10, 50, 90])
    return {"p10": round(float(p10), 2), "p50": round(float(p50), 2), "p90": round(float(p90), 2)}


def simulate_goal(request: GoalRequest, start: PortfolioStart, macro: MacroStart,
                  assumptions: Assumptions | None = None) -> dict[str, Any]:
    if start.size != 1:
        raise ValueError("goal simulation needs exactly one customer")
    a = assumptions or Assumptions()
    scale = np.ones(len(CATEGORIES))
    for cut in request.category_cuts:
        scale[CATEGORY_INDEX[cut.category]] = 1 - cut.cut_pct / 100
    target = request.target_amount

    baseline = _balances(start, request, macro, np.ones(len(CATEGORIES)), 0.0, a)
    changed = _balances(start, request, macro, scale, request.extra_monthly_saving, a)

    def probability(saving: float) -> float:
        balance = changed[-1] if saving == request.extra_monthly_saving else \
            _balances(start, request, macro, scale, saving, a)[-1]
        return float((balance >= target).mean())

    # Smallest extra monthly saving (with the category cuts) that reaches the target with the given confidence.
    required: float | None
    if probability(0.0) >= request.confidence:
        required = 0.0
    else:
        low, high = 0.0, max(target / request.months * 2, 1_000.0)
        if probability(high) < request.confidence:
            required = None
        else:
            for _ in range(30):
                mid = (low + high) / 2
                if probability(mid) >= request.confidence:
                    high = mid
                else:
                    low = mid
                if high - low < 10:
                    break
            required = round(high, -1)

    path = reference_path(macro, request.months)
    cut_savings = float((start.category_base[0] * (1 - scale)).sum())
    months = [m.isoformat()[:7] for m in path.months]
    return {
        "customerId": int(start.customer_id[0]),
        "targetAmount": target, "months": request.months, "horizonMonth": months[-1],
        "startBalance": round(float(start.liquidity0[0] + start.usd0[0] * path.usd_try[0]), 2),
        "probabilityWithoutChanges": round(float((baseline[-1] >= target).mean()), 4),
        "probabilityWithChanges": round(float((changed[-1] >= target).mean()), 4),
        "extraMonthlySaving": request.extra_monthly_saving,
        "categoryCuts": [c.model_dump(by_alias=True) for c in request.category_cuts],
        "monthlySavingFromCuts": round(cut_savings, 2),
        "confidence": request.confidence,
        "requiredExtraMonthlySaving": required,
        "balanceAtHorizonWithoutChanges": _band(baseline[-1]),
        "balanceAtHorizonWithChanges": _band(changed[-1]),
        "monthly": [{"month": months[t], "balance": _band(changed[t])} for t in range(request.months)],
        "assumptions": {
            "macroPath": "neutral continuation (trailing 12-month inflation, incomes indexed every 6 months)",
            "monthlyInflationPct": round(float(path.inflation[0]) * 100, 3),
            "priceLevelAtHorizon": round(float(path.price_index[-1]), 4),
            "targetInTodaysPrices": round(target / float(path.price_index[-1]), 2),
            "paths": request.paths, "seed": request.seed,
            "note": "nominal TRY; the extra saving is set aside from spending every month; existing liquid "
                    "savings (TRY and USD) count towards the target"}}

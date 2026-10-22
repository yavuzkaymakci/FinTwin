"""Turns run accumulators into the simulation result: portfolio distributions, expected and tail loss, segments,
delta against the baseline, rating migration, most affected customers and per-customer cash-flow bands."""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from app.features import SEGMENTS, SPEC_BY_NAME
from app.models.matrix import MATRIX_COLUMNS, to_matrix
from app.models.risk_artifact import RiskModelArtifact
from app.scoring.reasons import format_value
from app.simulation.engine import TRACKED, RunAccumulator
from app.simulation.macro import MacroPath
from app.simulation.start_state import PortfolioStart

BAND = (5, 50, 95)
LOSS_BAND = (5, 50, 95, 99)
MIGRATION_TO = [str(g) for g in range(1, 11)] + ["DEFAULT", "CHURN"]


def _stats(values: np.ndarray, quantiles: tuple[int, ...] = BAND, digits: int = 6) -> dict[str, float]:
    out = {"mean": round(float(np.mean(values)), digits)}
    for q in quantiles:
        out[f"p{q}"] = round(float(np.percentile(values, q)), digits)
    return out


def _rate(numerator: np.ndarray, denominator: float | np.ndarray) -> np.ndarray:
    return np.divide(numerator, denominator, out=np.zeros_like(numerator, dtype=float),
                     where=np.asarray(denominator) > 0)


def portfolio(acc: RunAccumulator, start: PortfolioStart, path: MacroPath) -> dict[str, Any]:
    credit_customers = float(start.credit.sum())
    customers = float(start.size)
    ead = float(acc.per_customer["ead_start"].sum())
    cumulative_defaults = acc.defaults.cumsum(1)
    cumulative_churns = acc.churns.cumsum(1)
    monthly = []
    for t, month in enumerate(path.months):
        means = {name: round(float(acc.feature_sum[t + 1, j] / acc.feature_count[t + 1, j]), 5)
                 if acc.feature_count[t + 1, j] else None for j, name in enumerate(TRACKED)}
        monthly.append({
            "month": month.isoformat()[:7],
            "defaultRate": _stats(_rate(acc.defaults[:, t], acc.alive_credit[:, t])),
            "churnRate": _stats(_rate(acc.churns[:, t], acc.alive[:, t])),
            "cumulativeDefaultRate": _stats(cumulative_defaults[:, t] / max(credit_customers, 1)),
            "cumulativeChurnRate": _stats(cumulative_churns[:, t] / max(customers, 1)),
            "meanPd": round(float(acc.pd_sum[t] / acc.pd_count[t]), 6) if acc.pd_count[t] else None,
            "featureMeans": means})
    loss = acc.loss
    return {
        "customers": int(customers), "creditCustomers": int(credit_customers), "eadStart": round(ead, 2),
        "meanPdStart": round(float(acc.per_customer["pd_start"][start.credit].mean()), 6) if credit_customers else 0,
        "meanChurnStart": round(float(acc.per_customer["churn_start"].mean()), 6),
        "startFeatureMeans": {name: round(float(acc.feature_sum[0, j] / acc.feature_count[0, j]), 5)
                              if acc.feature_count[0, j] else None for j, name in enumerate(TRACKED)},
        "defaultRate": _stats(acc.defaults.sum(1) / max(credit_customers, 1)),
        "churnRate": _stats(acc.churns.sum(1) / max(customers, 1)),
        "loss": _stats(loss, LOSS_BAND, digits=2),
        "expectedLoss": round(float(loss.mean()), 2),
        "lossP99": round(float(np.percentile(loss, 99)), 2),
        "unexpectedLossP99": round(float(np.percentile(loss, 99) - loss.mean()), 2),
        "expectedLossPctOfEad": round(float(loss.mean() / ead * 100), 4) if ead else 0.0,
        "monthly": monthly,
    }


def segments(acc: RunAccumulator, start: PortfolioStart) -> list[dict[str, Any]]:
    out = []
    for k, segment in enumerate(SEGMENTS):
        members = start.segment_index == k
        credit = float((members & start.credit).sum())
        count = float(members.sum())
        out.append({
            "segment": segment, "customers": int(count), "creditCustomers": int(credit),
            "eadStart": round(float(acc.per_customer["ead_start"][members].sum()), 2),
            "meanPdStart": round(float(acc.per_customer["pd_start"][members & start.credit].mean()), 6)
            if credit else 0.0,
            "defaultRate": _stats(acc.segment_defaults[:, k] / max(credit, 1)),
            "churnRate": _stats(acc.segment_churns[:, k] / max(count, 1)),
            "expectedLoss": round(float(acc.segment_loss[:, k].mean()), 2),
            "lossP99": round(float(np.percentile(acc.segment_loss[:, k], 99)), 2)})
    return out


def delta(scenario: RunAccumulator, baseline: RunAccumulator, start: PortfolioStart) -> dict[str, Any]:
    """Scenario minus baseline per path (common random numbers), so the difference has little Monte Carlo noise."""
    credit = max(float(start.credit.sum()), 1)
    customers = max(float(start.size), 1)
    out: dict[str, Any] = {
        "defaultRate": _stats((scenario.defaults.sum(1) - baseline.defaults.sum(1)) / credit),
        "churnRate": _stats((scenario.churns.sum(1) - baseline.churns.sum(1)) / customers),
        "loss": _stats(scenario.loss - baseline.loss, LOSS_BAND, digits=2),
        "segments": []}
    for k, segment in enumerate(SEGMENTS):
        members = start.segment_index == k
        seg_credit = max(float((members & start.credit).sum()), 1)
        seg_count = max(float(members.sum()), 1)
        out["segments"].append({
            "segment": segment,
            "defaultRate": _stats((scenario.segment_defaults[:, k] - baseline.segment_defaults[:, k]) / seg_credit),
            "churnRate": _stats((scenario.segment_churns[:, k] - baseline.segment_churns[:, k]) / seg_count),
            "expectedLoss": round(float((scenario.segment_loss[:, k] - baseline.segment_loss[:, k]).mean()), 2)})
    return out


def migration(acc: RunAccumulator) -> dict[str, Any]:
    counts = acc.migration
    totals = counts.sum(1, keepdims=True)
    rates = np.divide(counts, totals, out=np.zeros_like(counts), where=totals > 0)
    return {"from": list(range(1, 11)), "to": MIGRATION_TO,
            "counts": counts.astype(int).tolist(), "rates": np.round(rates, 5).tolist(),
            "note": "credit customers x paths; start grade from the PD at the start, end state at the horizon"}


def top_customers(acc: RunAccumulator, start: PortfolioStart, chosen: np.ndarray, scenario_end: np.ndarray,
                  reference_end: np.ndarray, pd_model: RiskModelArtifact) -> list[dict[str, Any]]:
    """Customers with the largest PD increase (start -> horizon), with the features driving the increase.

    Reasons compare SHAP contributions at the horizon of the central scenario path and of the central neutral
    reference path (no shocks): the features whose contribution to the PD the scenario raised the most, with their
    start, neutral and scenario values.
    """
    if len(chosen) == 0:
        return []
    x0 = to_matrix(pd.DataFrame([start.features0[i] for i in chosen]))
    x_scenario = pd.DataFrame(scenario_end, columns=MATRIX_COLUMNS, index=x0.index)
    x_reference = pd.DataFrame(reference_end, columns=MATRIX_COLUMNS, index=x0.index)
    c_scenario = pd_model.contributions(x_scenario)
    c_reference = pd_model.contributions(x_reference)
    out = []
    for row, i in enumerate(chosen):
        change = (c_scenario.iloc[row] - c_reference.iloc[row]).sort_values(ascending=False)
        reasons = []
        for feature, impact in change.items():
            if impact <= 1e-4 or len(reasons) == 5 or feature == "segment":
                continue
            spec = SPEC_BY_NAME[feature]
            before = x0.iloc[row][feature]
            neutral = x_reference.iloc[row][feature]
            after = x_scenario.iloc[row][feature]
            reasons.append({
                "feature": feature, "impact": round(float(impact), 4),
                "from": _round(before, 4), "neutral": _round(neutral, 4), "to": _round(after, 4),
                "text": f"{spec.label_en}: {_show(before, spec.kind, False)} -> {_show(after, spec.kind, False)} "
                        f"(neutral path {_show(neutral, spec.kind, False)})",
                "textTr": f"{spec.label_tr}: {_show(before, spec.kind, True)} → {_show(after, spec.kind, True)} "
                          f"(nötr patikada {_show(neutral, spec.kind, True)})"})
        pc = acc.per_customer
        out.append({
            "customerId": int(start.customer_id[i]), "segment": start.segment[i],
            "pdStart": round(float(pc["pd_start"][i]), 6), "pdHorizon": round(float(pc["pd_horizon"][i]), 6),
            "pdIncrease": round(float(pc["pd_horizon"][i] - pc["pd_start"][i]), 6),
            "defaultProbability": round(float(pc["default_probability"][i]), 6), "reasons": reasons})
    return out


def _show(value: Any, kind: str, tr: bool) -> str:
    if value is None or pd.isna(value):
        return "yok" if tr else "n/a"
    if kind == "flag":
        return ("evet" if value >= 0.5 else "hayır") if tr else ("yes" if value >= 0.5 else "no")
    return format_value(float(value), kind, tr)


def forecasts(acc: RunAccumulator, start: PortfolioStart, path: MacroPath) -> list[dict[str, Any]]:
    if acc.forecasts is None:
        return []
    months = [m.isoformat()[:7] for m in path.months]
    pc = acc.per_customer
    out = []
    for i in range(start.size):
        f = acc.forecasts[i]
        out.append({
            "customerId": int(start.customer_id[i]), "segment": start.segment[i],
            "pdStart": round(float(pc["pd_start"][i]), 6), "pdHorizon": round(float(pc["pd_horizon"][i]), 6),
            "defaultProbability": round(float(pc["default_probability"][i]), 6),
            "churnProbability": round(float(pc["churn_probability"][i]), 6),
            "startBalance": round(float(start.liquidity0[i] + start.usd0[i] * path.usd_try[0]), 2),
            "startLiquidityBufferMonths": _round(start.features0[i].get("liquidity_buffer_months"), 3),
            "months": [{"month": months[t],
                        "balance": {"p10": round(float(f[t, 0]), 2), "p50": round(float(f[t, 1]), 2),
                                    "p90": round(float(f[t, 2]), 2)},
                        "liquidityBufferMonths": {"p10": round(float(f[t, 3]), 3), "p50": round(float(f[t, 4]), 3),
                                                  "p90": round(float(f[t, 5]), 3)},
                        "cumulativeDefaultProbability": round(float(f[t, 6]), 5)} for t in range(len(months))]})
    return out


def _round(value: Any, digits: int) -> float | None:
    return None if value is None or pd.isna(value) else round(float(value), digits)

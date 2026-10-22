"""Runs a simulation request: scenario (and optional baseline with the same random numbers) -> result."""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any

import numpy as np

from app.models.risk_artifact import RiskModelArtifact
from app.simulation import results
from app.simulation.engine import central_features, run_scenarios
from app.simulation.macro import MacroStart, build_path, reference_path
from app.simulation.scenario import ScenarioParameters, SimulationRequest
from app.simulation.start_state import PortfolioStart


def _aligned_baseline(request: SimulationRequest) -> ScenarioParameters | None:
    """The baseline runs with the scenario's horizon, paths and seed (common random numbers)."""
    if request.baseline is None:
        return None
    scenario, baseline = request.scenario, request.baseline
    inflation = baseline.monthly_inflation_pct[:scenario.horizon_months]
    return baseline.model_copy(update={"horizon_months": scenario.horizon_months,
                                       "monthly_inflation_pct": inflation,
                                       "monte_carlo_paths": scenario.monte_carlo_paths, "seed": scenario.seed})


def simulate(request: SimulationRequest, start: PortfolioStart, macro: MacroStart, pd_model: RiskModelArtifact,
             churn_model: RiskModelArtifact, progress: Callable[[float], None] | None = None) -> dict[str, Any]:
    """Result dict (camelCase): ``summary`` (everything but forecasts) and ``forecasts`` (one entry per customer)."""
    began = time.perf_counter()
    scenario = request.scenario
    baseline = _aligned_baseline(request)
    path = build_path(scenario, macro)
    reference = reference_path(macro, scenario.horizon_months)
    paths = [path] if baseline is None else [path, build_path(baseline, macro)]
    keep = [request.include_forecasts] + ([False] if baseline is not None else [])
    accumulators = run_scenarios(start, paths, reference, request.assumptions, scenario.monte_carlo_paths,
                                 scenario.seed, pd_model, churn_model, keep, progress)
    acc = accumulators[0]
    summary: dict[str, Any] = {
        "startDate": start.start.isoformat(), "horizonMonths": path.horizon, "paths": scenario.monte_carlo_paths,
        "seed": scenario.seed, "modelVersions": {"pd": pd_model.version, "churn": churn_model.version},
        "scenario": scenario.model_dump(by_alias=True, mode="json"),
        "assumptions": request.assumptions.model_dump(by_alias=True, mode="json"),
        "macroPath": path.as_rows(), "referencePath": reference.as_rows(),
        "portfolio": results.portfolio(acc, start, path),
        "segments": results.segments(acc, start),
        "migration": results.migration(acc),
        "baseline": None, "deltaVsBaseline": None}
    if baseline is not None:
        base_acc = accumulators[1]
        summary["baseline"] = {"scenario": baseline.model_dump(by_alias=True, mode="json"),
                               "portfolio": results.portfolio(base_acc, start, paths[1]),
                               "segments": results.segments(base_acc, start)}
        summary["deltaVsBaseline"] = results.delta(acc, base_acc, start)

    increase = np.where(start.credit, acc.per_customer["pd_horizon"] - acc.per_customer["pd_start"], -np.inf)
    n = min(request.top_customers, int(start.credit.sum()))
    summary["topCustomers"] = []
    if n:
        chosen = np.argsort(-increase, kind="stable")[:n]
        chosen_start = start.subset(chosen)
        summary["topCustomers"] = results.top_customers(
            acc, start, chosen, central_features(chosen_start, path, request.assumptions),
            central_features(chosen_start, reference, request.assumptions), pd_model)
    summary["durationSeconds"] = round(time.perf_counter() - began, 2)
    return {"summary": summary, "forecasts": results.forecasts(acc, start, path)}

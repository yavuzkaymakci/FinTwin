"""Back-test: simulate from a past snapshot with the realised macro path and compare with what happened.

For each start month the portfolio at that snapshot is projected over 6 months with the realised CPI path,
USD/TRY and policy rate (from ``macro_indicator``) and the public wage-setting rules of the period. Simulated
6-month default and churn rates are compared with the events in ``customer_event`` for the same customers, in total
and per segment; projected feature means are compared with the actual snapshots 6 months later. The static
benchmark is the mean 6-month PD / churn probability at the start (no projection).

Usage: ``python -m app.simulation.backtest --start 2025-09-01 --start 2025-12-01 [--paths 200]``
"""

from __future__ import annotations

import argparse
import json
import logging
from datetime import date
from typing import Any

import numpy as np
import psutil

from app.db import read_frame
from app.features import SEGMENTS
from app.models import registry
from app.simulation.engine import TRACKED
from app.simulation.macro import MacroStart, add_months, load_macro_start
from app.simulation.runner import simulate
from app.simulation.scenario import (
    Assumptions,
    ScenarioParameters,
    SegmentWageRule,
    ShockProfile,
    SimulationRequest,
)
from app.simulation.start_state import PortfolioStart, load_start

log = logging.getLogger("backtest")

HORIZON = 6
#: Public wage-setting rules of the period (minimum wage once a year, pensions every six months by past inflation,
#: white-collar raises in January, business income following prices), the knowledge a bank has when back-testing.
REALISED_WAGE_RULES = [
    SegmentWageRule(segment="MIN_WAGE", wage_indexation_pct=100, wage_adjustment_lag_months=12),
    SegmentWageRule(segment="RETIREE", wage_indexation_pct=100, wage_adjustment_lag_months=6),
    SegmentWageRule(segment="WHITE_COLLAR", wage_indexation_pct=75, wage_adjustment_lag_months=12),
    SegmentWageRule(segment="SELF_EMPLOYED", wage_indexation_pct=85, wage_adjustment_lag_months=1),
    SegmentWageRule(segment="STUDENT", wage_indexation_pct=75, wage_adjustment_lag_months=12),
]

EVENTS_SQL = """
SELECT customer_id, event_type FROM customer_event
WHERE event_type IN ('DEFAULT', 'CHURN') AND event_date >= %s AND event_date < %s
"""


def realised_scenario(macro: MacroStart, start: date, paths: int, seed: int) -> ScenarioParameters:
    months = [add_months(start, t) for t in range(HORIZON)]
    inflation = [round(macro.inflation_history.get(m, 0.0) * 100, 4) for m in months]
    last = months[-1]
    usd_change = (macro.usd_try_history[last] / macro.usd_try - 1) * 100
    rate_change = round((macro.policy_history[last] - macro.policy_rate) * 10_000)
    return ScenarioParameters(
        horizon_months=HORIZON, usd_try_change_pct=round(usd_change, 3), policy_rate_change_bp=rate_change,
        monthly_inflation_pct=inflation, wage_indexation_pct=90, wage_adjustment_lag_months=6,
        segment_wage_rules=REALISED_WAGE_RULES, shock_profile=ShockProfile.GRADUAL, shock_ramp_months=HORIZON,
        monte_carlo_paths=paths, seed=seed)


def observed(start: PortfolioStart) -> dict[str, Any]:
    end = add_months(start.start, HORIZON)
    events = read_frame(EVENTS_SQL, (start.start, end))
    defaults = set(events.loc[events.event_type == "DEFAULT", "customer_id"])
    churns = set(events.loc[events.event_type == "CHURN", "customer_id"])
    ids = start.customer_id
    is_default = np.isin(ids, list(defaults)) & start.credit
    is_churn = np.isin(ids, list(churns))
    out = {"defaultRate": float(is_default.sum() / start.credit.sum()), "churnRate": float(is_churn.mean()),
           "defaults": int(is_default.sum()), "churns": int(is_churn.sum()), "segments": {}}
    for k, segment in enumerate(SEGMENTS):
        members = start.segment_index == k
        credit = members & start.credit
        out["segments"][segment] = {
            "defaultRate": float(is_default[credit].sum() / max(credit.sum(), 1)),
            "churnRate": float(is_churn[members].sum() / max(members.sum(), 1)),
            "defaults": int(is_default[credit].sum()), "churns": int(is_churn[members].sum())}
    # Actual feature means 6 months later for the customers still active then.
    later = load_start(end, customer_ids=ids.tolist())
    means = {}
    for name in TRACKED:
        values = np.array([f.get(name) for f in later.features0], dtype=float)
        if name == "liquidity_buffer_months":
            values = np.minimum(values, 60.0)
        means[name] = float(np.nanmean(values))
    out["featureMeansAfter"] = means
    return out


def run(start_date: date, paths: int, seed: int, assumptions: Assumptions) -> dict[str, Any]:
    pd_model, pd_meta = registry.load("pd")
    churn_model, _ = registry.load("churn")
    start = load_start(start_date)
    macro = load_macro_start(start_date)
    scenario = realised_scenario(macro, start_date, paths, seed)
    result = simulate(SimulationRequest(scenario=scenario, assumptions=assumptions, top_customers=0,
                                        include_forecasts=False), start, macro, pd_model, churn_model)
    summary = result["summary"]
    portfolio = summary["portfolio"]
    actual = observed(start)
    segments = {}
    for row in summary["segments"]:
        segments[row["segment"]] = {
            "simulatedDefaultRate": row["defaultRate"], "simulatedChurnRate": row["churnRate"],
            "staticPd": row["meanPdStart"], **{f"observed{k[0].upper()}{k[1:]}": v
                                              for k, v in actual["segments"][row["segment"]].items()}}
    return {
        "start": start_date.isoformat(), "customers": start.size, "creditCustomers": int(start.credit.sum()),
        "pdModel": pd_model.version, "pdTrainingPeriods": pd_meta["data"]["periods"],
        "scenario": scenario.model_dump(by_alias=True, mode="json"),
        "simulatedDefaultRate": portfolio["defaultRate"], "simulatedChurnRate": portfolio["churnRate"],
        "staticDefaultRate": portfolio["meanPdStart"], "staticChurnRate": portfolio["meanChurnStart"],
        "observedDefaultRate": actual["defaultRate"], "observedChurnRate": actual["churnRate"],
        "observedDefaults": actual["defaults"], "observedChurns": actual["churns"],
        "expectedLoss": portfolio["expectedLoss"], "lossP99": portfolio["lossP99"],
        "featureMeansStart": portfolio["startFeatureMeans"],
        "featureMeansSimulatedAfter": portfolio["monthly"][-1]["featureMeans"],
        "featureMeansObservedAfter": actual["featureMeansAfter"],
        "monthly": [{"month": m["month"], "cumulativeDefaultRate": m["cumulativeDefaultRate"]["mean"],
                     "meanPd": m["meanPd"]} for m in portfolio["monthly"]],
        "segments": segments, "durationSeconds": summary["durationSeconds"]}


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", action="append", type=date.fromisoformat, required=True)
    parser.add_argument("--paths", type=int, default=200)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--assumptions", type=json.loads, default={}, help="JSON overrides, e.g. '{\"cardCcf\": 0}'")
    parser.add_argument("--output", help="JSON report path (default artifacts/reports/backtest-<time>.json)")
    args = parser.parse_args()
    assumptions = Assumptions.model_validate(args.assumptions)
    reports = []
    for start in args.start:
        report = run(start, args.paths, args.seed, assumptions)
        reports.append(report)
        log.info("%s: default simulated %.4f (P5 %.4f, P95 %.4f) static %.4f observed %.4f | churn simulated %.4f "
                 "static %.4f observed %.4f | %.1f s", start, report["simulatedDefaultRate"]["mean"],
                 report["simulatedDefaultRate"]["p5"], report["simulatedDefaultRate"]["p95"],
                 report["staticDefaultRate"], report["observedDefaultRate"], report["simulatedChurnRate"]["mean"],
                 report["staticChurnRate"], report["observedChurnRate"], report["durationSeconds"])
        for segment, row in report["segments"].items():
            log.info("  %-13s default sim %.4f static %.4f obs %.4f (%d) | churn sim %.4f obs %.4f (%d)", segment,
                     row["simulatedDefaultRate"]["mean"], row["staticPd"], row["observedDefaultRate"],
                     row["observedDefaults"], row["simulatedChurnRate"]["mean"], row["observedChurnRate"],
                     row["observedChurns"])
        log.info("  features start %s", report["featureMeansStart"])
        log.info("  features sim   %s", report["featureMeansSimulatedAfter"])
        log.info("  features obs   %s", report["featureMeansObservedAfter"])
    out = {"assumptions": assumptions.model_dump(by_alias=True), "backtests": reports,
           "peakRssMb": round(psutil.Process().memory_info().rss / 2 ** 20)}
    path = args.output or str(registry.artifacts_dir() / "reports" / f"backtest-{registry.new_version()}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    log.info("report written to %s", path)


if __name__ == "__main__":
    main()

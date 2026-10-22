"""Runs the preset scenarios (``simulation.scenario`` where ``preset``) against the BASE scenario and prints the
portfolio results; the report goes to ``artifacts/reports/presets-<time>.json``.

Usage: ``python -m app.simulation.run_presets [--start 2026-10-01] [--paths 200]``
"""

from __future__ import annotations

import argparse
import json
import logging
import time
from datetime import date
from typing import Any

from app.db import read_frame
from app.models import registry
from app.simulation.macro import load_macro_start
from app.simulation.memory import PeakMemory
from app.simulation.runner import simulate
from app.simulation.scenario import ScenarioParameters, SimulationRequest
from app.simulation.start_state import load_start

log = logging.getLogger("presets")

PRESETS_SQL = "SELECT code, name, parameters::text AS parameters FROM simulation.scenario WHERE preset ORDER BY id"


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", type=date.fromisoformat, help="snapshot month start (default: current state)")
    parser.add_argument("--paths", type=int, help="override the presets' Monte Carlo paths")
    args = parser.parse_args()

    presets = read_frame(PRESETS_SQL)
    scenarios = {row.code: (row.name, ScenarioParameters.model_validate_json(row.parameters))
                 for row in presets.itertuples(index=False)}
    if args.paths:
        scenarios = {code: (name, s.model_copy(update={"monte_carlo_paths": args.paths}))
                     for code, (name, s) in scenarios.items()}
    pd_model, _ = registry.load("pd")
    churn_model, _ = registry.load("churn")
    start = load_start(args.start)
    macro = load_macro_start(start.start)
    log.info("start %s: %d customers (%d with credit exposure)", start.start, start.size, start.credit.sum())
    base = scenarios["BASE"][1]
    report: dict[str, Any] = {"start": start.start.isoformat(), "customers": start.size, "runs": {}}
    for code, (name, scenario) in scenarios.items():
        request = SimulationRequest(scenario=scenario, baseline=None if code == "BASE" else base, top_customers=5)
        began = time.perf_counter()
        with PeakMemory() as memory:
            result = simulate(request, start, macro, pd_model, churn_model)
        summary = result["summary"]
        summary["peakRssMb"] = round(memory.peak_mb)
        summary["wallSeconds"] = round(time.perf_counter() - began, 1)
        report["runs"][code] = {"name": name, **summary}
        p = summary["portfolio"]
        delta = summary["deltaVsBaseline"]
        log.info("%-12s default %.2f%% [P5 %.2f, P95 %.2f] churn %.2f%% EL %.0f (%.2f%% of EAD) P99 loss %.0f%s"
                 " | %.0f s, peak RSS %d MB", code, p["defaultRate"]["mean"] * 100, p["defaultRate"]["p5"] * 100,
                 p["defaultRate"]["p95"] * 100, p["churnRate"]["mean"] * 100, p["expectedLoss"],
                 p["expectedLossPctOfEad"], p["lossP99"],
                 "" if delta is None else f" | vs BASE default {delta['defaultRate']['mean'] * 100:+.2f} pp "
                                          f"EL {delta['loss']['mean']:+.0f}",
                 summary["wallSeconds"], summary["peakRssMb"])
        for row in summary["segments"]:
            log.info("    %-13s default %.2f%% churn %.2f%% EL %.0f", row["segment"], row["defaultRate"]["mean"] * 100,
                     row["churnRate"]["mean"] * 100, row["expectedLoss"])
    path = registry.artifacts_dir() / "reports" / f"presets-{registry.new_version()}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    log.info("report written to %s", path)


if __name__ == "__main__":
    main()

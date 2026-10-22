"""Process-wide simulation job manager (FastAPI dependency) running requests against the database and models."""

from __future__ import annotations

from collections.abc import Callable
from functools import lru_cache
from typing import Any

from app.scoring.service import get_models
from app.simulation.jobs import JobManager
from app.simulation.macro import load_macro_start
from app.simulation.runner import simulate
from app.simulation.scenario import SimulationRequest
from app.simulation.start_state import load_start


def run_request(request: SimulationRequest, progress: Callable[[float], None]) -> dict[str, Any]:
    scorer = get_models().require_risk()
    start = load_start(request.start_date, request.customer_ids)
    macro = load_macro_start(start.start)
    return simulate(request, start, macro, scorer.pd_model, scorer.churn_model, progress)


@lru_cache(maxsize=1)
def get_job_manager() -> JobManager:
    return JobManager(run_request)

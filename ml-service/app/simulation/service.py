"""Process-wide simulation job manager (FastAPI dependency) running requests against the database and models."""

from __future__ import annotations

from collections.abc import Callable
from functools import lru_cache
from typing import Any

from app.scoring.service import get_models
from app.simulation.goals import GoalRequest, simulate_goal
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


def run_goal(request: GoalRequest) -> dict[str, Any]:
    """Savings-goal simulation from the customer's current twin (raises LookupError for unknown customers)."""
    try:
        start = load_start(None, [request.customer_id])
    except ValueError as e:
        raise LookupError(f"No active twin for customer {request.customer_id}") from e
    return simulate_goal(request, start, load_macro_start(start.start))


def get_goal_runner() -> Callable[[GoalRequest], dict[str, Any]]:
    return run_goal

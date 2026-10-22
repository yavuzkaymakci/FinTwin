from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.main import app
from app.simulation.goals import GoalRequest, simulate_goal
from app.simulation.service import get_goal_runner
from tests.simulation_fixtures import macro_start, portfolio


def goal(**overrides) -> GoalRequest:
    values = {"customer_id": 1, "target_amount": 300_000, "months": 24, "paths": 300}
    values.update(overrides)
    return GoalRequest(**values)


@pytest.fixture(scope="module")
def saver():
    """One customer who saves part of their income every month."""
    return portfolio(1, seed=5, stressed=False)


def test_more_saving_and_cuts_raise_the_probability(saver) -> None:
    base = simulate_goal(goal(), saver, macro_start())
    target = base["balanceAtHorizonWithoutChanges"]["p50"] * 1.2
    plain = simulate_goal(goal(target_amount=target), saver, macro_start())
    saving = simulate_goal(goal(target_amount=target, extra_monthly_saving=3_000), saver, macro_start())
    cuts = simulate_goal(goal(target_amount=target, category_cuts=[{"category": "RESTAURANT", "cutPct": 100}]),
                         saver, macro_start())
    assert plain["probabilityWithoutChanges"] == plain["probabilityWithChanges"] < 0.5
    assert saving["probabilityWithChanges"] > plain["probabilityWithChanges"]
    assert saving["probabilityWithoutChanges"] == plain["probabilityWithoutChanges"]  # same random numbers
    assert cuts["monthlySavingFromCuts"] > 0
    assert cuts["balanceAtHorizonWithChanges"]["p50"] > cuts["balanceAtHorizonWithoutChanges"]["p50"]


def test_required_saving_reaches_the_confidence(saver) -> None:
    target = simulate_goal(goal(), saver, macro_start())["balanceAtHorizonWithoutChanges"]["p50"] * 1.5
    result = simulate_goal(goal(target_amount=target, confidence=0.8), saver, macro_start())
    required = result["requiredExtraMonthlySaving"]
    assert required is not None and required > 0
    check = simulate_goal(goal(target_amount=target, extra_monthly_saving=required), saver, macro_start())
    assert check["probabilityWithChanges"] >= 0.8
    below = simulate_goal(goal(target_amount=target, extra_monthly_saving=max(required - 500, 0)), saver,
                          macro_start())
    assert below["probabilityWithChanges"] < 0.8 + 1e-9


def test_reachable_target_needs_no_extra_saving_and_bands_are_ordered(saver) -> None:
    result = simulate_goal(goal(target_amount=1_000), saver, macro_start())
    assert result["probabilityWithoutChanges"] == 1.0 and result["requiredExtraMonthlySaving"] == 0.0
    assert len(result["monthly"]) == 24
    for month in result["monthly"]:
        assert month["balance"]["p10"] <= month["balance"]["p50"] <= month["balance"]["p90"]
    assert result["assumptions"]["targetInTodaysPrices"] < 1_000  # prices rise over two years


def test_long_horizons_beyond_scenario_limits_are_supported(saver) -> None:
    assert len(simulate_goal(goal(months=60, paths=100), saver, macro_start())["monthly"]) == 60


@pytest.mark.parametrize("bad", [{"target_amount": 0}, {"months": 0}, {"months": 121},
                                 {"category_cuts": [{"category": "CASINO", "cutPct": 50}]},
                                 {"category_cuts": [{"category": "RESTAURANT", "cutPct": 150}]}])
def test_invalid_requests(bad: dict) -> None:
    with pytest.raises(ValidationError):
        goal(**bad)


def test_api() -> None:
    seen = []

    def runner(request: GoalRequest) -> dict:
        seen.append(request)
        if request.customer_id == 404:
            raise LookupError("No active twin for customer 404")
        return simulate_goal(request.model_copy(update={"customer_id": 1}), portfolio(1, seed=5, stressed=False),
                             macro_start())

    app.dependency_overrides[get_goal_runner] = lambda: runner
    try:
        client = TestClient(app)
        body = {"customerId": 7, "targetAmount": 200000, "months": 12, "categoryCuts": [
            {"category": "RESTAURANT", "cutPct": 50}], "paths": 200}
        response = client.post("/v1/goals/simulate", json=body)
        assert response.status_code == 200
        assert {"probabilityWithChanges", "requiredExtraMonthlySaving", "monthly"} <= response.json().keys()
        assert seen[0].category_cuts[0].cut_pct == 50
        assert client.post("/v1/goals/simulate", json={**body, "customerId": 404}).status_code == 404
        assert client.post("/v1/goals/simulate", json={**body, "months": 500}).status_code == 422
    finally:
        app.dependency_overrides.clear()

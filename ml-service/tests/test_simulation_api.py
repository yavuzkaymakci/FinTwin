from __future__ import annotations

import time
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.scoring.service import LoadedModels, get_models
from app.simulation.jobs import JobManager
from app.simulation.runner import simulate
from app.simulation.service import get_job_manager
from tests import model_fixtures
from tests.simulation_fixtures import macro_start, portfolio

REQUEST = {"scenario": {"horizonMonths": 3, "usdTryChangePct": 20, "monthlyInflationPct": [3.0],
                        "monteCarloPaths": 20, "seed": 5},
           "baseline": {"horizonMonths": 3, "monthlyInflationPct": [2.0], "monteCarloPaths": 20},
           "topCustomers": 3}


@pytest.fixture(scope="module")
def client() -> Iterator[TestClient]:
    scorer = model_fixtures.risk_scorer()
    models = LoadedModels(risk=scorer, categorizer=None, metadata={})
    start = portfolio(60)

    def runner(request, progress):
        if request.scenario.seed == 13:
            raise RuntimeError("boom")
        return simulate(request, start, macro_start(), scorer.pd_model, scorer.churn_model, progress)

    jobs = JobManager(runner)
    app.dependency_overrides[get_models] = lambda: models
    app.dependency_overrides[get_job_manager] = lambda: jobs
    yield TestClient(app)
    app.dependency_overrides.clear()
    jobs.shutdown()


def wait(client: TestClient, job_id: str) -> dict:
    for _ in range(300):
        body = client.get(f"/v1/simulations/{job_id}").json()
        if body["status"] in ("SUCCEEDED", "FAILED"):
            return body
        time.sleep(0.1)
    raise AssertionError("simulation did not finish")


def test_submits_a_job_and_returns_results_when_done(client: TestClient) -> None:
    response = client.post("/v1/simulations", json=REQUEST)
    assert response.status_code == 202
    submitted = response.json()
    assert submitted["status"] in ("QUEUED", "RUNNING") and submitted["result"] is None
    body = wait(client, submitted["jobId"])
    assert body["status"] == "SUCCEEDED" and body["progress"] == 100.0
    assert body["durationSeconds"] > 0 and body["peakRssMb"] > 0
    result = body["result"]
    assert {"portfolio", "segments", "migration", "topCustomers", "macroPath", "baseline",
            "deltaVsBaseline"} <= result.keys()
    assert {"mean", "p5", "p50", "p95"} <= result["portfolio"]["defaultRate"].keys()
    assert {"expectedLoss", "lossP99"} <= result["portfolio"].keys()

    page = client.get(f"/v1/simulations/{submitted['jobId']}/forecasts", params={"limit": 25}).json()
    assert page["total"] == 60 and len(page["items"]) == 25
    one = client.get(f"/v1/simulations/{submitted['jobId']}/forecasts", params={"customerId": 7}).json()
    assert one["total"] == 1 and len(one["items"][0]["months"]) == 3
    assert {"p10", "p50", "p90"} == one["items"][0]["months"][0]["balance"].keys()


def test_failed_job_reports_the_error(client: TestClient) -> None:
    job_id = client.post("/v1/simulations", json={**REQUEST, "scenario": {**REQUEST["scenario"], "seed": 13}}
                         ).json()["jobId"]
    body = wait(client, job_id)
    assert body["status"] == "FAILED" and "boom" in body["error"]
    assert client.get(f"/v1/simulations/{job_id}/forecasts").status_code == 409


def test_unknown_job_and_invalid_scenarios(client: TestClient) -> None:
    assert client.get("/v1/simulations/nope").status_code == 404
    invalid = {"scenario": {"horizonMonths": 3, "monthlyInflationPct": [3.0], "policyRateChangeBp": 99999}}
    response = client.post("/v1/simulations", json=invalid)
    assert response.status_code == 422
    assert "policyRateChangeBp" in str(response.json())


def test_no_models_is_503() -> None:
    app.dependency_overrides[get_models] = lambda: LoadedModels(risk=None, categorizer=None, metadata={})
    try:
        response = TestClient(app).post("/v1/simulations", json=REQUEST)
    finally:
        app.dependency_overrides.pop(get_models)
    assert response.status_code == 503 and response.json()["error"] == "MODEL_UNAVAILABLE"

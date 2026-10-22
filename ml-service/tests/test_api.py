from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.scoring.service import LoadedModels, get_models
from tests import model_fixtures

HIGH_RISK = {"cardLimit": 20000, "cardDebt": 19500, "utilizationRate": 0.975, "incomeEstimate": 15000,
             "debtToIncome": 0.55, "activeLoans": 1, "liquidityBufferMonths": 0.1, "spend90d": 9000,
             "spendByCategory90d": {"GROCERY": 4000, "CASH_WITHDRAWAL": 2500}, "avgMonthlyExpense": 18000,
             "latePaymentCount": 2, "daysSinceLastLatePayment": 20, "incomeMonthsObserved": 6,
             "unknownFieldFromANewerBackend": 1}
LOW_RISK = {**HIGH_RISK, "cardDebt": 1000, "utilizationRate": 0.05, "debtToIncome": 0.05,
            "liquidityBufferMonths": 8, "latePaymentCount": 0, "daysSinceLastLatePayment": None}


@pytest.fixture(scope="module")
def client() -> TestClient:
    models = LoadedModels(risk=model_fixtures.risk_scorer(), categorizer=model_fixtures.categorizer(),
                          metadata={})
    app.dependency_overrides[get_models] = lambda: models
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_scores_a_customer_with_grade_and_reasons(client: TestClient) -> None:
    response = client.post("/v1/risk/score", json={"customerId": 7, "segment": "MIN_WAGE", "features": HIGH_RISK})
    assert response.status_code == 200
    body = response.json()
    assert 0 < body["pd"] < 1 and 0 < body["churn"] < 1
    assert 1 <= body["riskGrade"] <= 10
    assert body["pdApplicable"] is True
    assert body["pdModelVersion"] == "test-pd"
    assert 1 <= len(body["pdReasons"]) <= 5
    reason = body["pdReasons"][0]
    assert {"code", "feature", "value", "portfolioMean", "impact", "text", "textTr"} <= reason.keys()
    assert all(r["impact"] > 0 for r in body["pdReasons"])
    assert any(r["feature"] == "utilization_rate" for r in body["pdReasons"])


def test_riskier_profile_scores_higher(client: TestClient) -> None:
    high = client.post("/v1/risk/score", json={"customerId": 1, "segment": "MIN_WAGE", "features": HIGH_RISK})
    low = client.post("/v1/risk/score", json={"customerId": 2, "segment": "MIN_WAGE", "features": LOW_RISK})
    assert high.json()["pd"] > low.json()["pd"]
    assert high.json()["riskGrade"] >= low.json()["riskGrade"]


def test_batch_scoring_keeps_order(client: TestClient) -> None:
    items = [{"customerId": i, "segment": "STUDENT", "features": HIGH_RISK if i % 2 else LOW_RISK}
             for i in range(10)]
    response = client.post("/v1/risk/score/batch", json={"items": items})
    assert response.status_code == 200
    assert [s["customerId"] for s in response.json()["scores"]] == list(range(10))


def test_batch_size_is_limited(client: TestClient) -> None:
    items = [{"customerId": i, "features": LOW_RISK} for i in range(1001)]
    assert client.post("/v1/risk/score/batch", json={"items": items}).status_code == 422


def test_explain_returns_all_contributions(client: TestClient) -> None:
    body = client.post("/v1/risk/explain", json={"customerId": 7, "segment": "RETIREE", "features": HIGH_RISK}).json()
    assert "utilization_rate" in body["pdContributions"] and "segment" in body["pdContributions"]
    assert body["modelFeatures"]["utilization_rate"] == pytest.approx(0.975)


def test_categorize(client: TestClient) -> None:
    response = client.post("/v1/categorize", json={"items": [
        {"rawDescription": "KARDESLER MKT 9999 USKUDAR IST", "channel": "POS", "direction": "DEBIT"},
        {"rawDescription": "MAAS ODEMESI MARMARA LOJISTIK", "channel": "EFT_FAST", "direction": "CREDIT"}]})
    assert response.status_code == 200
    predictions = response.json()["predictions"]
    assert [p["category"] for p in predictions] == ["GROCERY", "SALARY"]
    assert len(predictions[0]["alternatives"]) == 2
    assert response.json()["modelVersion"] == "test-nlp"


def test_validation_errors(client: TestClient) -> None:
    assert client.post("/v1/categorize", json={"items": []}).status_code == 422
    assert client.post("/v1/risk/score", json={"segment": "STUDENT"}).status_code == 422


def test_missing_models_return_503() -> None:
    app.dependency_overrides[get_models] = lambda: LoadedModels(risk=None, categorizer=None, metadata={})
    try:
        c = TestClient(app)
        response = c.post("/v1/risk/score", json={"customerId": 1, "features": LOW_RISK})
        assert response.status_code == 503
        assert response.json()["error"] == "MODEL_UNAVAILABLE"
        assert c.post("/v1/categorize", json={"items": [{"rawDescription": "X"}]}).status_code == 503
        assert c.get("/v1/models/active").json() == {"models": []}
    finally:
        app.dependency_overrides.clear()

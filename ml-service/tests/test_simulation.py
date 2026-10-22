from __future__ import annotations

import dataclasses
from datetime import date

import numpy as np
import pytest
from pydantic import ValidationError

from app.models.risk_artifact import RiskModelArtifact
from app.simulation.engine import monthly_hazard
from app.simulation.macro import build_path, card_monthly_rate, inflation_since_last_raise, reference_path
from app.simulation.runner import simulate
from app.simulation.scenario import ScenarioParameters, ShockProfile, SimulationRequest
from tests import model_fixtures
from tests.simulation_fixtures import START, macro_start, portfolio, scenario


@pytest.fixture(scope="module")
def models() -> tuple[RiskModelArtifact, RiskModelArtifact]:
    return model_fixtures.risk_artifact("pd"), model_fixtures.risk_artifact("churn")


def run(models, start=None, top: int = 0, forecasts: bool = False, baseline: ScenarioParameters | None = None,
        **overrides):
    request = SimulationRequest(scenario=scenario(**overrides), baseline=baseline, top_customers=top,
                                include_forecasts=forecasts)
    return simulate(request, start if start is not None else portfolio(), macro_start(), *models)


# ---------------------------------------------------------------- building blocks


def test_monthly_hazard_compounds_back_to_the_six_month_probability() -> None:
    p = np.array([0.0, 0.001, 0.02, 0.3, 0.9])
    assert np.allclose(1 - (1 - monthly_hazard(p)) ** 6, p)


def test_raises_follow_the_lag_and_compensate_inflation_since_the_last_raise() -> None:
    macro = macro_start()  # 2 % a month before the start (September)
    path = build_path(scenario(horizon_months=12, wage_indexation_pct=80, wage_adjustment_lag_months=6), macro)
    index = path.wage_index["MIN_WAGE"]
    months = [m.month for m in path.months]
    raised = [months[t - 1] for t in range(1, 13) if index[t] != index[t - 1]]
    assert raised == [1, 7]  # January and July
    # January: 80 % of the inflation since the July raise (Jul-Aug before the start, Sep-Dec simulated).
    january = months.index(1) + 1
    since_july = 1.02 ** 2 * path.price_index[january - 1] - 1
    assert index[january] == pytest.approx(1 + 0.8 * since_july)
    assert inflation_since_last_raise(START, 6, macro.inflation_history) == pytest.approx(1.02 ** 2 - 1)
    assert inflation_since_last_raise(START, 1, macro.inflation_history) == pytest.approx(0.02)


def test_immediate_and_gradual_shocks() -> None:
    macro = macro_start()
    immediate = build_path(scenario(usd_try_change_pct=30, policy_rate_change_bp=500), macro)
    gradual = build_path(scenario(usd_try_change_pct=30, policy_rate_change_bp=500,
                                  shock_profile=ShockProfile.GRADUAL, shock_ramp_months=3), macro)
    assert immediate.usd_try[1] == pytest.approx(52.0) and immediate.usd_try[6] == pytest.approx(52.0)
    assert gradual.usd_try[1:4] == pytest.approx([44.0, 48.0, 52.0]) and gradual.usd_try[6] == pytest.approx(52.0)
    assert immediate.policy_rate[1] == pytest.approx(0.45)
    assert immediate.card_rate[1] > immediate.card_rate[0]
    low_rate = dataclasses.replace(macro, policy_rate=0.20)
    assert build_path(scenario(policy_rate_change_bp=-3000), low_rate).policy_rate[1] == 0.0
    assert card_monthly_rate(np.array([0.0, 2.0])) == pytest.approx([0.0175, 0.05])


def test_reference_path_keeps_real_income_after_each_raise() -> None:
    path = reference_path(macro_start(), 12)
    index = path.wage_index["WHITE_COLLAR"]
    for t, month in enumerate(path.months, start=1):
        if month.month in (1, 7):  # catch-up raise covers all inflation up to the previous month
            assert index[t] == pytest.approx(path.price_index[t - 1] * 1.02 ** 2)
    assert path.policy_rate[-1] == pytest.approx(0.40)
    assert path.inflation == pytest.approx(np.full(12, 0.02))


@pytest.mark.parametrize("bad", [
    {"horizon_months": 0}, {"monte_carlo_paths": 5}, {"monthly_inflation_pct": []},
    {"horizon_months": 2, "monthly_inflation_pct": [1, 2, 3]}, {"usd_try_change_pct": 500},
    {"segment_wage_rules": [{"segment": "PILOT", "wageIndexationPct": 50, "wageAdjustmentLagMonths": 6}]},
])
def test_invalid_scenarios_are_rejected(bad: dict) -> None:
    with pytest.raises(ValidationError):
        scenario(**bad)


def test_scenario_schema_is_camel_case_and_strict() -> None:
    parsed = ScenarioParameters.model_validate({"horizonMonths": 3, "monthlyInflationPct": [2.5], "seed": 1})
    assert parsed.horizon_months == 3 and parsed.inflation(3) == 0.025
    with pytest.raises(ValidationError):
        ScenarioParameters.model_validate({"horizonMonths": 3, "monthlyInflationPct": [2.5], "fxShock": 1})
    with pytest.raises(ValidationError):
        SimulationRequest.model_validate({"scenario": {"monthlyInflationPct": [2]}, "startDate": "2025-09-15"})
    schema = ScenarioParameters.model_json_schema(by_alias=True)
    assert all("description" in field for field in schema["properties"].values())


# ---------------------------------------------------------------- logic tests


def test_same_seed_gives_the_same_result_and_chunking_does_not_matter(models, monkeypatch) -> None:
    first = run(models, forecasts=True)
    second = run(models, forecasts=True)
    monkeypatch.setenv("SIM_CHUNK_ROWS", "500")  # 12 customers per chunk instead of all at once
    chunked = run(models, forecasts=True)
    other_seed = run(models, seed=8)
    for result in (second, chunked):
        assert result["summary"]["portfolio"] == first["summary"]["portfolio"]
        assert result["forecasts"] == first["forecasts"]
    assert other_seed["summary"]["portfolio"]["loss"] != first["summary"]["portfolio"]["loss"]


def test_a_scenario_equal_to_the_baseline_has_zero_delta_and_a_tiny_shock_a_tiny_one(models) -> None:
    base = scenario(usd_try_change_pct=10)
    same = run(models, baseline=base, usd_try_change_pct=10)["summary"]
    assert same["deltaVsBaseline"]["defaultRate"]["mean"] == 0
    assert same["deltaVsBaseline"]["loss"]["mean"] == 0
    tiny = run(models, baseline=base, usd_try_change_pct=10.5)["summary"]
    assert abs(tiny["deltaVsBaseline"]["defaultRate"]["mean"]) < 0.002


def test_neutral_scenario_reproduces_the_start_pd(models) -> None:
    """Under the neutral reference path the expected 6-month default rate equals the mean PD at the start."""
    start = portfolio(400)
    reference = reference_path(macro_start(), 6)
    neutral = scenario(monthly_inflation_pct=[float(reference.inflation[0] * 100)],
                       usd_try_change_pct=(reference.usd_try[-1] / reference.usd_try[0] - 1) * 100,
                       shock_profile=ShockProfile.GRADUAL, shock_ramp_months=6, monte_carlo_paths=200)
    request = SimulationRequest(scenario=neutral, top_customers=0, include_forecasts=False)
    request.assumptions.systematic_income_volatility = 0.0
    portfolio_result = simulate(request, start, macro_start(), *models)["summary"]["portfolio"]
    assert portfolio_result["defaultRate"]["mean"] == pytest.approx(portfolio_result["meanPdStart"], rel=0.1)


@pytest.mark.parametrize("shock", ["inflation", "fx", "combined"])
def test_default_rate_rises_monotonically_with_the_shock(models, shock: str) -> None:
    start = portfolio(300, stressed=True)
    rates = []
    for level in range(4):
        overrides = {"inflation": {"monthly_inflation_pct": [2.0 + 2 * level], "wage_indexation_pct": 70},
                     "fx": {"usd_try_change_pct": 25.0 * level},
                     "combined": {"monthly_inflation_pct": [2.0 + 1.5 * level], "wage_indexation_pct": 80,
                                  "usd_try_change_pct": 15.0 * level, "policy_rate_change_bp": 300 * level}}[shock]
        rates.append(run(models, start=start, **overrides)["summary"]["portfolio"]["defaultRate"]["mean"])
    assert all(b >= a - 1e-9 for a, b in zip(rates, rates[1:], strict=False)), rates
    assert rates[-1] > rates[0]


def test_a_rate_shock_raises_card_debt_through_the_card_rate(models) -> None:
    start = portfolio(200, stressed=True)
    calm = run(models, start=start)["summary"]["portfolio"]
    shocked = run(models, start=start, policy_rate_change_bp=1500)["summary"]["portfolio"]
    assert shocked["monthly"][-1]["featureMeans"]["utilization_rate"] > calm["monthly"][-1]["featureMeans"][
        "utilization_rate"]
    assert shocked["defaultRate"]["mean"] >= calm["defaultRate"]["mean"]


def test_usd_holders_are_hit_less_by_an_fx_shock(models) -> None:
    lira_only = portfolio(300, seed=11, stressed=True)
    usd_holders = portfolio(300, seed=11, stressed=True, usd=2_000)
    base = scenario()
    shock = {"usd_try_change_pct": 60, "monthly_inflation_pct": [5.0], "baseline": base}
    delta_lira = run(models, start=lira_only, **shock)["summary"]["deltaVsBaseline"]["defaultRate"]["mean"]
    delta_usd = run(models, start=usd_holders, **shock)["summary"]["deltaVsBaseline"]["defaultRate"]["mean"]
    assert delta_lira > 0
    assert delta_usd < delta_lira


def test_defaulted_customers_are_counted_once_and_outputs_are_consistent(models) -> None:
    result = run(models, start=portfolio(150, stressed=True), forecasts=True, top=5, horizon_months=12,
                 monthly_inflation_pct=[6.0], wage_indexation_pct=50)
    summary = result["summary"]
    p = summary["portfolio"]
    assert 0 < p["defaultRate"]["mean"] <= 1
    cumulative = [m["cumulativeDefaultRate"]["mean"] for m in p["monthly"]]
    assert cumulative == sorted(cumulative) and cumulative[-1] == pytest.approx(p["defaultRate"]["mean"])
    assert p["loss"]["p99"] >= p["loss"]["p95"] >= p["loss"]["p50"] and p["lossP99"] >= p["expectedLoss"]
    migration = np.array(summary["migration"]["counts"])
    assert migration.sum() == p["creditCustomers"] * summary["paths"]
    rates = np.array(summary["migration"]["rates"])
    assert np.allclose(rates.sum(1)[migration.sum(1) > 0], 1)
    assert sum(s["customers"] for s in summary["segments"]) == p["customers"]
    for f in result["forecasts"]:
        probabilities = [m["cumulativeDefaultProbability"] for m in f["months"]]
        assert probabilities == sorted(probabilities) and probabilities[-1] == pytest.approx(f["defaultProbability"])
        for m in f["months"]:
            assert m["balance"]["p10"] <= m["balance"]["p50"] <= m["balance"]["p90"]
            assert m["liquidityBufferMonths"]["p10"] <= m["liquidityBufferMonths"]["p90"]
    top = summary["topCustomers"]
    assert len(top) == 5 and top[0]["pdIncrease"] >= top[-1]["pdIncrease"]
    assert all({"text", "textTr", "from", "to", "neutral"} <= r.keys() for c in top for r in c["reasons"])


def test_baseline_with_another_horizon_is_aligned_to_the_scenario(models) -> None:
    summary = run(models, baseline=scenario(horizon_months=12, monte_carlo_paths=10),
                  horizon_months=6)["summary"]
    assert summary["baseline"]["scenario"]["horizonMonths"] == 6
    assert summary["baseline"]["scenario"]["monteCarloPaths"] == summary["paths"]
    assert summary["deltaVsBaseline"] is not None


def test_start_date_must_be_a_month_start() -> None:
    assert SimulationRequest(scenario=scenario(), start_date=date(2025, 9, 1)).start_date == START

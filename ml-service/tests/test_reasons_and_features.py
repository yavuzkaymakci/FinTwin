from __future__ import annotations

import pytest

from app.features import FEATURES, NEVER_LATE_DAYS, from_twin_features, has_credit_exposure
from app.scoring.reasons import reason, top_reasons


def test_readable_reason_in_both_languages() -> None:
    r = reason("utilization_rate", 0.92, 0.8, {"utilization_rate": 0.31}, {})
    assert r["text"] == "Credit card limit utilisation 92%, far above the portfolio average (31%)"
    assert r["textTr"] == "Kredi kartı limit kullanımı %92, portföy ortalamasının (%31) çok üstünde"
    assert r["code"] == "UTILIZATION_RATE_HIGH"


def test_low_buffer_reason() -> None:
    r = reason("liquidity_buffer_months", 0.4, 0.5, {"liquidity_buffer_months": 3.2}, {})
    assert r["text"] == "Liquidity buffer 0.4 months, far below the portfolio average (3.2 months)"
    assert r["textTr"] == "Likidite tamponu 0,4 ay, portföy ortalamasının (3,2 ay) çok altında"


def test_segment_missing_and_flag_reasons() -> None:
    seg = reason("segment", "STUDENT", 0.3, {}, {"STUDENT": 0.05, "WHITE_COLLAR": 0.01})
    assert seg["code"] == "SEGMENT_STUDENT"
    assert seg["text"] == "Customer segment STUDENT (segment event rate 5.0%)"
    assert seg["textTr"] == "Müşteri segmenti STUDENT (segment olay oranı %5,0)"
    loans = reason("active_loans", 0.0, 0.4, {"active_loans": 0.42}, {})
    assert loans["text"] == "Active loans 0, far below the portfolio average (0.4)"
    missing = reason("income_cv", None, 0.2, {"income_cv": 0.1}, {})
    assert missing["code"] == "INCOME_CV_MISSING" and missing["value"] is None
    flag = reason("no_income_observed", 1.0, 0.2, {"no_income_observed": 0.05}, {})
    assert flag["textTr"] == "Son aylarda gelir gözlenmedi: evet"


def test_top_reasons_only_risk_increasing_strongest_first() -> None:
    contributions = {"utilization_rate": 0.9, "debt_to_income": -0.4, "late_payment_count": 0.3,
                     "liquidity_buffer_months": 1.2}
    features = {"utilization_rate": 0.9, "debt_to_income": 0.1, "late_payment_count": 2,
                "liquidity_buffer_months": 0.2}
    means = {k: 0.5 for k in features}
    result = top_reasons(contributions, features, means, {}, top=5)
    assert [r["feature"] for r in result] == ["liquidity_buffer_months", "utilization_rate", "late_payment_count"]


def test_feature_mapping() -> None:
    twin = {"cardLimit": 10000, "cardDebt": 5000, "utilizationRate": 0.5, "incomeEstimate": 20000,
            "totalDebt": 15000, "checkingBalance": 3000, "depositBalance": 1000, "spend90d": 10000,
            "spendByCategory90d": {"CASH_WITHDRAWAL": 1000, "RENT": 3000, "RESTAURANT": 500, "TRAVEL": 500},
            "avgMonthlyExpense": 10000, "incomeMonthsObserved": 6, "daysSinceLastLatePayment": None,
            "usdBalance": 12.5}
    f = from_twin_features(twin, "STUDENT")
    assert set(f) == set(FEATURES)
    assert f["card_debt_to_income"] == pytest.approx(0.25)
    assert f["total_debt_to_income"] == pytest.approx(0.75)
    assert f["expense_to_income"] == pytest.approx(0.5)
    assert f["deposit_share"] == pytest.approx(0.25)
    assert f["cash_share"] == pytest.approx(0.1) and f["rent_share"] == pytest.approx(0.3)
    assert f["discretionary_share"] == pytest.approx(0.1)
    assert f["days_since_last_late"] == NEVER_LATE_DAYS
    assert f["has_card"] == 1.0 and f["usd_holder"] == 1.0 and f["no_income_observed"] == 0.0
    assert from_twin_features(twin, "ALIEN")["segment"] is None


def test_ratios_without_income_are_missing() -> None:
    f = from_twin_features({"incomeEstimate": 0, "incomeMonthsObserved": 3, "cardDebt": 100}, "MIN_WAGE")
    assert f["card_debt_to_income"] is None
    assert f["no_income_observed"] == 1.0
    assert has_credit_exposure({"cardDebt": 1}) and not has_credit_exposure({"cardLimit": 0})

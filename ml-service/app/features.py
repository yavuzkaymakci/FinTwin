"""Model features derived from twin features.

Training (monthly twin snapshots) and serving (current twin state) call the same function, so the features a
model sees in production are computed exactly like the ones it was trained on. Inputs are the twin's
``TwinFeatures`` JSON (camelCase, see backend ``com.fintwin.twin.projection.TwinFeatures``) plus the segment.

The length of the observed history (``incomeMonthsObserved``) is not a feature: it only reflects when the twin's
history started (it rises from 0 to 6 during the first months of data and is always 6 afterwards), which made it
the most unstable variable between training and test periods (PSI ~2).

Money levels are deliberately not used directly: prices and incomes inflate by ~30 %/year in the data, which would
make nominal amounts drift between training and scoring. Amounts are expressed relative to income or spending.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

SEGMENTS = ["MIN_WAGE", "RETIREE", "SELF_EMPLOYED", "STUDENT", "WHITE_COLLAR"]


@dataclass(frozen=True)
class FeatureSpec:
    """Human-readable description of a model feature, used for explanations."""

    name: str
    label_en: str
    label_tr: str
    # pct (0..1 shown as %), ratio (x), months, days, count, flag, category
    kind: str


FEATURE_SPECS: list[FeatureSpec] = [
    FeatureSpec("utilization_rate", "Credit card limit utilisation", "Kredi kartı limit kullanımı", "pct"),
    FeatureSpec("debt_to_income", "Debt service to income", "Borç ödemesinin gelire oranı", "pct"),
    FeatureSpec("card_debt_to_income", "Card debt relative to monthly income", "Kart borcunun aylık gelire oranı",
                "ratio"),
    FeatureSpec("total_debt_to_income", "Total debt relative to monthly income",
                "Toplam borcun aylık gelire oranı", "ratio"),
    FeatureSpec("expense_to_income", "Monthly expenses relative to income", "Aylık giderlerin gelire oranı", "pct"),
    FeatureSpec("liquidity_buffer_months", "Liquidity buffer", "Likidite tamponu", "months"),
    FeatureSpec("deposit_share", "Share of savings in time deposits", "Birikimin vadeli hesaptaki payı", "pct"),
    FeatureSpec("spend_trend", "Spending trend (30 vs 90 days)", "Harcama trendi (30/90 gün)", "pct"),
    FeatureSpec("income_cv", "Income volatility", "Gelir dalgalanması", "pct"),
    FeatureSpec("salary_day_deviation", "Salary day irregularity", "Maaş günü düzensizliği", "days"),
    FeatureSpec("no_income_observed", "No income observed recently", "Son aylarda gelir gözlenmedi", "flag"),
    FeatureSpec("late_payment_count", "Late payments so far", "Gecikmeli ödeme sayısı", "count"),
    FeatureSpec("days_since_last_late", "Days since the last late payment", "Son gecikmeden bu yana geçen gün",
                "days"),
    FeatureSpec("active_loans", "Active loans", "Aktif kredi sayısı", "count"),
    FeatureSpec("has_card", "Has a credit card", "Kredi kartı sahibi", "flag"),
    FeatureSpec("usd_holder", "Holds a USD account balance", "Döviz (USD) bakiyesi var", "flag"),
    FeatureSpec("cash_share", "Share of spending withdrawn as cash", "Harcamada nakit çekim payı", "pct"),
    FeatureSpec("rent_share", "Share of spending on rent", "Harcamada kira payı", "pct"),
    FeatureSpec("discretionary_share", "Share of spending on restaurants, entertainment and travel",
                "Restoran, eğlence ve seyahat harcaması payı", "pct"),
    FeatureSpec("ecommerce_share", "Share of spending online", "Harcamada e-ticaret payı", "pct"),
    FeatureSpec("essentials_share", "Share of spending on groceries and bills", "Market ve fatura harcaması payı",
                "pct"),
    FeatureSpec("segment", "Customer segment", "Müşteri segmenti", "category"),
]

FEATURES: list[str] = [s.name for s in FEATURE_SPECS]
NUMERIC_FEATURES: list[str] = [s.name for s in FEATURE_SPECS if s.kind != "category"]
CATEGORICAL_FEATURES: list[str] = ["segment"]
SPEC_BY_NAME: dict[str, FeatureSpec] = {s.name: s for s in FEATURE_SPECS}

#: days_since_last_late for customers who were never late (finite, so linear models can use it).
NEVER_LATE_DAYS = 3650.0


def _num(value: Any) -> float | None:
    if value is None:
        return None
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def _ratio(numerator: float | None, denominator: float | None) -> float | None:
    if numerator is None or denominator is None or denominator <= 0:
        return None
    return numerator / denominator


def _share(categories: Mapping[str, Any], names: list[str], total: float | None) -> float | None:
    if not total or total <= 0:
        return None
    return sum(_num(categories.get(n)) or 0.0 for n in names) / total


def from_twin_features(twin: Mapping[str, Any], segment: str | None) -> dict[str, Any]:
    """Model features from a twin feature map (``TwinFeatures`` JSON) and the customer segment."""
    income = _num(twin.get("incomeEstimate"))
    card_debt = _num(twin.get("cardDebt")) or 0.0
    card_limit = _num(twin.get("cardLimit")) or 0.0
    total_debt = _num(twin.get("totalDebt")) or 0.0
    checking = _num(twin.get("checkingBalance")) or 0.0
    deposit = _num(twin.get("depositBalance")) or 0.0
    spend90 = _num(twin.get("spend90d"))
    categories = twin.get("spendByCategory90d") or {}
    days_since_late = _num(twin.get("daysSinceLastLatePayment"))
    buffer = _num(twin.get("liquidityBufferMonths"))
    income_months = _num(twin.get("incomeMonthsObserved")) or 0.0
    return {
        "utilization_rate": _num(twin.get("utilizationRate")),
        "debt_to_income": _num(twin.get("debtToIncome")),
        "card_debt_to_income": _ratio(card_debt, income),
        "total_debt_to_income": _ratio(total_debt, income),
        "expense_to_income": _ratio(_num(twin.get("avgMonthlyExpense")), income),
        "liquidity_buffer_months": None if buffer is None else min(buffer, 60.0),
        "deposit_share": _ratio(deposit, checking + deposit),
        "spend_trend": _num(twin.get("spendTrend")),
        "income_cv": _num(twin.get("incomeCv")),
        "salary_day_deviation": _num(twin.get("salaryDayDeviation")),
        "no_income_observed": 1.0 if income_months > 0 and not income else 0.0,
        "late_payment_count": _num(twin.get("latePaymentCount")) or 0.0,
        "days_since_last_late": NEVER_LATE_DAYS if days_since_late is None else days_since_late,
        "active_loans": _num(twin.get("activeLoans")) or 0.0,
        "has_card": 1.0 if card_limit > 0 else 0.0,
        "usd_holder": 1.0 if (_num(twin.get("usdBalance")) or 0.0) > 0 else 0.0,
        "cash_share": _share(categories, ["CASH_WITHDRAWAL"], spend90),
        "rent_share": _share(categories, ["RENT"], spend90),
        "discretionary_share": _share(categories, ["RESTAURANT", "ENTERTAINMENT", "TRAVEL"], spend90),
        "ecommerce_share": _share(categories, ["ECOMMERCE"], spend90),
        "essentials_share": _share(categories, ["GROCERY", "UTILITIES", "TELECOM"], spend90),
        "segment": segment if segment in SEGMENTS else None,
    }


def has_credit_exposure(twin: Mapping[str, Any]) -> bool:
    """PD population: customers with a card limit, card debt or an active loan."""
    return ((_num(twin.get("cardLimit")) or 0) > 0 or (_num(twin.get("cardDebt")) or 0) > 0
            or (_num(twin.get("activeLoans")) or 0) > 0)

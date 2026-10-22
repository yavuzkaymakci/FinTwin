"""Human-readable reasons from SHAP contributions.

The top contributors that *increase* the score are turned into short sentences comparing the customer's value with
the portfolio average of the training population, e.g. "Credit card limit utilisation 92%, far above the portfolio
average (31%)" / "Kredi kartı limit kullanımı %92, portföy ortalamasının (%31) çok üstünde".
"""

from __future__ import annotations

import math
from typing import Any

from app.features import SPEC_BY_NAME, FeatureSpec


def _comparison(value: float, mean: float) -> tuple[str, str, str]:
    """(direction code, English phrase, Turkish phrase) of value relative to the mean."""
    if mean == 0:
        return ("HIGH", "above", "üstünde") if value > 0 else ("LOW", "below", "altında")
    r = value / mean
    if r >= 2:
        return "HIGH", "far above", "çok üstünde"
    if r >= 1.15:
        return "HIGH", "above", "üstünde"
    if r <= 0.5:
        return "LOW", "far below", "çok altında"
    if r <= 0.87:
        return "LOW", "below", "altında"
    return "TYPICAL", "close to", "civarında"


def _fmt(value: float, kind: str, tr: bool, mean: bool = False) -> str:
    if kind == "pct":
        number = f"{value * 100:.0f}"
        return f"%{number}" if tr else f"{number}%"
    if kind == "ratio":
        number = f"{value:.1f}".replace(".", ",") if tr else f"{value:.1f}"
        return f"{number}x"
    if kind == "months":
        number = f"{value:.1f}".replace(".", ",") if tr else f"{value:.1f}"
        return f"{number} ay" if tr else f"{number} months"
    if kind == "days":
        return f"{value:.0f} gün" if tr else f"{value:.0f} days"
    if mean:  # averages of counts are fractional
        return f"{value:.1f}".replace(".", ",") if tr else f"{value:.1f}"
    return f"{value:.0f}"


def reason(feature: str, value: Any, impact: float, means: dict[str, float],
           segment_rates: dict[str, float]) -> dict[str, Any]:
    spec: FeatureSpec = SPEC_BY_NAME[feature]
    base = {"feature": feature, "impact": round(float(impact), 4)}
    if spec.kind == "category":
        # The segment's contribution is conditional on the other characteristics (interactions), so the text only
        # states the segment and its historical rate, without claiming that the segment as such is riskier.
        rate = segment_rates.get(str(value))
        detail_en = f" (segment event rate {rate * 100:.1f}%)" if rate is not None else ""
        detail_tr = f" (segment olay oranı %{rate * 100:.1f})".replace(".", ",") if rate is not None else ""
        return {**base, "code": f"SEGMENT_{value}", "value": value, "portfolioMean": None,
                "text": f"{spec.label_en} {value}{detail_en}", "textTr": f"{spec.label_tr} {value}{detail_tr}"}
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return {**base, "code": f"{feature.upper()}_MISSING", "value": None, "portfolioMean": means.get(feature),
                "text": f"{spec.label_en} not available", "textTr": f"{spec.label_tr} bilinmiyor"}
    value = float(value)
    if spec.kind == "flag":
        yes = value >= 0.5
        return {**base, "code": f"{feature.upper()}_{'YES' if yes else 'NO'}", "value": value,
                "portfolioMean": means.get(feature),
                "text": f"{spec.label_en}: {'yes' if yes else 'no'}",
                "textTr": f"{spec.label_tr}: {'evet' if yes else 'hayır'}"}
    mean = means.get(feature, 0.0)
    direction, en, tr = _comparison(value, mean)
    return {**base, "code": f"{feature.upper()}_{direction}", "value": value, "portfolioMean": mean,
            "text": f"{spec.label_en} {_fmt(value, spec.kind, False)}, {en} the portfolio average "
                    f"({_fmt(mean, spec.kind, False, mean=True)})",
            "textTr": f"{spec.label_tr} {_fmt(value, spec.kind, True)}, portföy ortalamasının "
                      f"({_fmt(mean, spec.kind, True, mean=True)}) {tr}"}


def top_reasons(contributions: dict[str, float], features: dict[str, Any], means: dict[str, float],
                segment_rates: dict[str, float], top: int = 5) -> list[dict[str, Any]]:
    """Up to ``top`` reasons among the features that increase the score, strongest first."""
    positive = sorted(((f, c) for f, c in contributions.items() if c > 0), key=lambda fc: -fc[1])[:top]
    return [reason(f, features.get(f), c, means, segment_rates) for f, c in positive]

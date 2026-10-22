"""Request/response schemas of the model API (camelCase on the wire, like the backend)."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class TwinFeaturesIn(ApiModel):
    """The twin's feature vector (backend ``TwinFeatures`` JSON). Unknown fields are ignored."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    evaluated_at: str | None = None
    checking_balance: float | None = None
    deposit_balance: float | None = None
    usd_balance: float | None = None
    card_debt: float | None = None
    card_limit: float | None = None
    utilization_rate: float | None = None
    loan_outstanding: float | None = None
    total_debt: float | None = None
    active_loans: int | None = None
    spend30d: float | None = Field(default=None, alias="spend30d")
    spend90d: float | None = Field(default=None, alias="spend90d")
    spend_by_category30d: dict[str, float] | None = Field(default=None, alias="spendByCategory30d")
    spend_by_category90d: dict[str, float] | None = Field(default=None, alias="spendByCategory90d")
    spend_trend: float | None = None
    income_estimate: float | None = None
    income_months_observed: int | None = None
    income_cv: float | None = None
    salary_day_deviation: float | None = None
    monthly_obligations: float | None = None
    debt_to_income: float | None = None
    late_payment_count: int | None = None
    days_since_last_late_payment: int | None = None
    avg_monthly_expense: float | None = None
    liquidity_buffer_months: float | None = None

    def as_twin_map(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True)


class RiskScoreRequest(ApiModel):
    customer_id: int
    segment: str | None = None
    features: TwinFeaturesIn


class RiskBatchRequest(ApiModel):
    items: list[RiskScoreRequest] = Field(max_length=1000)


class Reason(ApiModel):
    code: str
    feature: str
    value: float | str | None
    portfolio_mean: float | None
    impact: float = Field(description="SHAP contribution to the log-odds of the uncalibrated model")
    text: str
    text_tr: str


class RiskScore(ApiModel):
    customer_id: int
    pd: float = Field(description="probability of default (90+ DPD) within 6 months")
    churn: float = Field(description="probability of churn within 6 months")
    risk_grade: int = Field(ge=1, le=10)
    pd_applicable: bool = Field(description="customer has credit exposure (the PD model's population)")
    pd_reasons: list[Reason]
    churn_reasons: list[Reason]
    pd_model_version: str
    churn_model_version: str


class RiskBatchResponse(ApiModel):
    scores: list[RiskScore]


class RiskExplanation(RiskScore):
    pd_contributions: dict[str, float]
    churn_contributions: dict[str, float]
    model_features: dict[str, Any]


class CategorizeItem(ApiModel):
    raw_description: str = Field(min_length=1, max_length=256)
    channel: str | None = None
    direction: str | None = None
    account_type: str | None = None


class CategorizeRequest(ApiModel):
    items: list[CategorizeItem] = Field(min_length=1, max_length=5000)


class CategoryAlternative(ApiModel):
    category: str
    probability: float


class CategoryPrediction(CategoryAlternative):
    alternatives: list[CategoryAlternative]


class CategorizeResponse(ApiModel):
    predictions: list[CategoryPrediction]
    model_version: str


class ModelInfo(ApiModel):
    name: str
    version: str
    trained_at: str | None = None
    summary: dict[str, Any]


class ActiveModels(ApiModel):
    models: list[ModelInfo]

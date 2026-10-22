"""Typed scenario and simulation request schemas (camelCase on the wire, like the backend).

``ScenarioParameters`` is also the tool schema the scenario assistant fills from natural language (step 6), so
every field states its unit and range. The backend validates the same fields with Bean Validation
(``com.fintwin.simulation.dto.ScenarioParameters``); keep both in sync.
"""

from __future__ import annotations

from datetime import date
from enum import StrEnum
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

from app.features import SEGMENTS


class SchemaModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class ShockProfile(StrEnum):
    IMMEDIATE = "IMMEDIATE"
    GRADUAL = "GRADUAL"


InflationPct = Annotated[float, Field(ge=-5, le=30)]


class SegmentWageRule(SchemaModel):
    """Wage rule for one customer segment, overriding the scenario-wide rule."""

    segment: str = Field(description=f"Customer segment, one of {', '.join(SEGMENTS)}")
    wage_indexation_pct: float = Field(ge=0, le=150, description="Share of past inflation passed into incomes, %")
    wage_adjustment_lag_months: int = Field(ge=0, le=12, description="Months between income raises (see scenario)")

    @field_validator("segment")
    @classmethod
    def known_segment(cls, value: str) -> str:
        if value not in SEGMENTS:
            raise ValueError(f"unknown segment {value!r}; expected one of {SEGMENTS}")
        return value


class ScenarioParameters(SchemaModel):
    """A macroeconomic scenario over the simulation horizon, relative to the starting month."""

    horizon_months: int = Field(12, ge=1, le=36, description="Simulation horizon in months (1-36)")
    usd_try_change_pct: float = Field(
        0, ge=-50, le=200,
        description="Change of the USD/TRY rate against the starting month, in percent (30 = lira loses 30 %, "
                    "i.e. USD/TRY x1.30). Reached at once or over shockRampMonths (shockProfile).")
    policy_rate_change_bp: int = Field(
        0, ge=-3000, le=3000,
        description="Change of the CBRT annual policy rate against the starting month, in basis points "
                    "(500 = +5 percentage points). Card and deposit rates follow it.")
    monthly_inflation_pct: list[InflationPct] = Field(
        min_length=1, max_length=36,
        description="Monthly CPI inflation path in percent per month, starting with the first simulated month; "
                    "the last value repeats until the horizon (e.g. [4.5, 3.0, 2.0]).")
    wage_indexation_pct: float = Field(
        90, ge=0, le=150,
        description="Share of the inflation since the previous raise that incomes (wages, pensions, business "
                    "income) receive at each raise, in percent (80 = raises cover 80 % of past inflation).")
    wage_adjustment_lag_months: int = Field(
        6, ge=0, le=12,
        description="Months between income raises, aligned to January (6 = January and July, 12 = January "
                    "only); 0 or 1 = monthly indexation to the previous month's inflation.")
    segment_wage_rules: list[SegmentWageRule] = Field(
        default_factory=list, max_length=len(SEGMENTS),
        description="Optional per-segment overrides of the wage rule (e.g. minimum wage earners only once a year).")
    shock_profile: ShockProfile = Field(
        ShockProfile.IMMEDIATE,
        description="IMMEDIATE: the USD/TRY and policy rate changes apply from the first month; GRADUAL: they "
                    "build up linearly over shockRampMonths.")
    shock_ramp_months: int = Field(6, ge=1, le=36, description="Months to reach the full change when GRADUAL")
    monte_carlo_paths: int = Field(200, ge=10, le=2000, description="Number of Monte Carlo paths (10-2000)")
    seed: int = Field(42, ge=0, le=2_147_483_647, description="Random seed; same scenario and seed = same result")

    @model_validator(mode="after")
    def consistent(self) -> ScenarioParameters:
        if len(self.monthly_inflation_pct) > self.horizon_months:
            raise ValueError("monthlyInflationPct has more values than horizonMonths")
        segments = [r.segment for r in self.segment_wage_rules]
        if len(set(segments)) != len(segments):
            raise ValueError("segmentWageRules lists a segment twice")
        return self

    def inflation(self, month: int) -> float:
        """Monthly inflation (fraction) of simulated month ``month`` (1-based)."""
        path = self.monthly_inflation_pct
        return path[min(month, len(path)) - 1] / 100.0

    def ramp(self, month: int) -> float:
        """Share of the USD/TRY and policy rate change in effect in month ``month`` (1-based)."""
        if self.shock_profile is ShockProfile.IMMEDIATE:
            return 1.0
        return min(month / self.shock_ramp_months, 1.0)

    def wage_rule(self, segment: str) -> tuple[float, int]:
        for rule in self.segment_wage_rules:
            if rule.segment == segment:
                return rule.wage_indexation_pct / 100.0, rule.wage_adjustment_lag_months
        return self.wage_indexation_pct / 100.0, self.wage_adjustment_lag_months


class Assumptions(SchemaModel):
    """Engine assumptions (documented in docs/simulation.md); defaults apply when omitted."""

    lgd_unsecured: float = Field(0.70, ge=0, le=1, description="Loss given default of cards and personal loans")
    lgd_mortgage: float = Field(0.25, ge=0, le=1, description="Loss given default of mortgages")
    card_ccf: float = Field(0.30, ge=0, le=1, description="Credit conversion factor of the undrawn card limit")
    discretionary_elasticity: float = Field(
        0.6, ge=0, le=2, description="Elasticity of real discretionary spending to real income")
    fx_pass_through: float = Field(
        0.5, ge=0, le=1, description="Weight of USD/TRY (vs CPI) in prices of electronics and travel")
    revolving_share: float = Field(
        0.35, ge=0, le=1, description="Share of the card balance that revolves and bears interest")
    deposit_tax_rate: float = Field(0.15, ge=0, le=1, description="Withholding tax on deposit interest")
    systematic_income_volatility: float = Field(
        0.02, ge=0, le=0.2, description="Monthly std. dev. of the common (economy-wide) income factor per path")
    systematic_persistence: float = Field(0.7, ge=0, lt=1, description="AR(1) coefficient of the common factor")
    income_volatility_scale: float = Field(
        1.0, ge=0, le=3, description="Idiosyncratic monthly income noise = scale x the customer's income CV")
    expense_volatility: float = Field(0.08, ge=0, le=0.5, description="Idiosyncratic monthly spending noise")
    expense_reversion_6m: float = Field(
        0.34, ge=0, le=1,
        description="Share of a customer's deviation from the segment's expense-to-income ratio that persists "
                    "after 6 months (calibrated on the twin snapshots: 6-month regression slope 0.34)")
    liquidity_floor_months: float = Field(
        0.5, ge=0, le=6, description="Monthly outflows kept as cash before paying down borrowed card debt")



class SimulationRequest(SchemaModel):
    scenario: ScenarioParameters
    baseline: ScenarioParameters | None = Field(
        None, description="Baseline scenario simulated with the same random numbers; results include the delta")
    start_date: date | None = Field(
        None, description="Month-start twin snapshot to start from (e.g. 2025-09-01); null = current twin state")
    assumptions: Assumptions = Field(default_factory=Assumptions)
    top_customers: int = Field(20, ge=0, le=200, description="Most affected customers to report with reasons")
    customer_ids: list[int] | None = Field(None, max_length=20_000, description="Restrict to these customers")
    include_forecasts: bool = True

    @field_validator("start_date")
    @classmethod
    def month_start(cls, value: date | None) -> date | None:
        if value is not None and value.day != 1:
            raise ValueError("startDate must be the first day of a month (snapshots are taken at month start)")
        return value

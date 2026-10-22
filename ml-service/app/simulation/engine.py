"""Vectorised Monte Carlo projection of the twins under a macro path, scored monthly with the PD and churn models.

For every month ``t = 1..H`` and every (path, customer):

1. the features at the start of month ``t`` are scored with the active PD and churn models (6-month
   probabilities), converted to monthly hazards ``h = 1 - (1 - p)^(1/6)`` and default / churn events are sampled
   for customers still active (an event is terminal: the customer is not counted again);
2. the month's cash flows are projected (income with raises and shocks, inflation-indexed essentials, partly
   elastic discretionary spending, fixed loan installments, card interest tied to the policy rate, deposit
   interest, USD revaluation, buffer -> USD -> card limit drawdown when the cash flow is negative);
3. the twin features are recomputed from the projected state.

Customers are processed in chunks with all paths at once (arrays of shape ``(paths, customers)``), so per-customer
bands are exact while per-path portfolio sums are accumulated across chunks. Random numbers come from per-customer
seed sequences, so results do not depend on the chunk size and scenario / baseline runs share them (common random
numbers).
"""

from __future__ import annotations

import os
from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np

from app.features import NEVER_LATE_DAYS, SEGMENTS
from app.grades import GRADE_BOUNDS
from app.models.matrix import MATRIX_COLUMNS, to_matrix
from app.models.risk_artifact import RiskModelArtifact
from app.simulation.macro import MacroPath
from app.simulation.scenario import Assumptions
from app.simulation.start_state import CATEGORIES, CATEGORY_INDEX, DISCRETIONARY, FX_SENSITIVE, PortfolioStart

PD_HORIZON_MONTHS = 6
DAYS_PER_MONTH = 30.4
#: Shortfall (TRY) below which a month is not treated as a missed payment.
MISS_THRESHOLD = 50.0
#: Features whose portfolio means are tracked per month (alive customers), e.g. for the back-test.
TRACKED = ["liquidity_buffer_months", "utilization_rate", "expense_to_income", "debt_to_income",
           "card_debt_to_income", "spend_trend"]

_COLUMN = {name: i for i, name in enumerate(MATRIX_COLUMNS)}
_DISCRETIONARY = np.array([c in DISCRETIONARY for c in CATEGORIES])
_FX = np.array([c in FX_SENSITIVE for c in CATEGORIES])
_SHARES = {
    "cash_share": ["CASH_WITHDRAWAL"], "rent_share": ["RENT"],
    "discretionary_share": ["RESTAURANT", "ENTERTAINMENT", "TRAVEL"], "ecommerce_share": ["ECOMMERCE"],
    "essentials_share": ["GROCERY", "UTILITIES", "TELECOM"]}
_SHARE_INDEX = {name: [CATEGORY_INDEX[c] for c in cats] for name, cats in _SHARES.items()}
#: Features the projection moves relative to the observed start value (lower, upper bound). The engine recomputes
#: them from its simplified state; adding (projected - recomputed start) to the observed value keeps month 1
#: identical to the twin and lets only the projected change reach the models.
ANCHORED = {
    "utilization_rate": (0.0, None), "debt_to_income": (0.0, None), "card_debt_to_income": (0.0, None),
    "total_debt_to_income": (0.0, None), "expense_to_income": (0.0, None), "liquidity_buffer_months": (0.0, 60.0),
    "spend_trend": (-1.0, None), "cash_share": (0.0, 1.0), "rent_share": (0.0, 1.0),
    "discretionary_share": (0.0, 1.0), "ecommerce_share": (0.0, 1.0), "essentials_share": (0.0, 1.0)}
_ANCHORED_COLUMNS = [_COLUMN[name] for name in ANCHORED]
_ANCHOR_LOW = np.array([lo for lo, _ in ANCHORED.values()], dtype=np.float32)
_ANCHOR_HIGH = np.array([np.inf if hi is None else hi for _, hi in ANCHORED.values()], dtype=np.float32)


def monthly_hazard(probability_6m: np.ndarray) -> np.ndarray:
    """Constant monthly hazard with the same 6-month cumulative probability: 1 - (1 - p)^(1/6)."""
    return 1 - np.power(1 - np.clip(probability_6m, 0, 1 - 1e-9), 1 / PD_HORIZON_MONTHS)


def grade_of(probability: np.ndarray) -> np.ndarray:
    """Master-scale grade 1..10 (vectorised :func:`app.grades.grade`)."""
    return 1 + np.searchsorted(np.asarray(GRADE_BOUNDS), probability, side="right")


def threads() -> int:
    """LightGBM prediction threads (SIM_THREADS; default half the cores, at most 8). Threads add no memory."""
    return int(os.environ.get("SIM_THREADS", str(max(1, min(8, (os.cpu_count() or 2) // 2)))))


def chunk_rows() -> int:
    """Rows (paths x customers) per chunk; bounds the memory of one chunk (~100k rows = a few hundred MB)."""
    return int(os.environ.get("SIM_CHUNK_ROWS", "100000"))


def predict(model: RiskModelArtifact, x: np.ndarray) -> np.ndarray:
    """Calibrated probabilities for a float matrix with :data:`MATRIX_COLUMNS` (fast path, no DataFrame)."""
    raw = model.booster.booster_.predict(x, num_threads=threads())
    return np.clip(model.calibrator.predict(raw), 1e-5, 1 - 1e-5)


def predict_logit(model: RiskModelArtifact, x: np.ndarray, paths: int, columns: np.ndarray | None = None
                  ) -> np.ndarray:
    """Log-odds (P, c) for a (P * c, F) matrix; with ``columns`` only those customers are scored (others 0)."""
    c = x.shape[0] // paths
    if columns is None:
        return logit(predict(model, x)).reshape(paths, c)
    out = np.zeros((paths, c))
    if columns.any():
        selected = x.reshape(paths, c, -1)[:, columns, :].reshape(-1, x.shape[1])
        out[:, columns] = logit(predict(model, selected)).reshape(paths, -1)
    return out


@dataclass
class Draws:
    """Standard normal / uniform draws of a chunk, shape (H, P, c)."""

    income: np.ndarray
    expense: np.ndarray
    default: np.ndarray
    churn: np.ndarray

    @classmethod
    def for_customers(cls, seed: int, customer_ids: np.ndarray, horizon: int, paths: int) -> Draws:
        arrays = np.empty((4, horizon, paths, len(customer_ids)))
        for j, customer_id in enumerate(customer_ids):
            streams = np.random.SeedSequence([seed, 1, int(customer_id)]).spawn(4)
            arrays[0, :, :, j] = np.random.default_rng(streams[0]).standard_normal((horizon, paths))
            arrays[1, :, :, j] = np.random.default_rng(streams[1]).standard_normal((horizon, paths))
            arrays[2, :, :, j] = np.random.default_rng(streams[2]).random((horizon, paths))
            arrays[3, :, :, j] = np.random.default_rng(streams[3]).random((horizon, paths))
        return cls(*arrays)

    @classmethod
    def zero(cls, horizon: int, customers: int) -> Draws:
        """Central path: no shocks, no events (uniforms of 1 never fire)."""
        zeros = np.zeros((horizon, 1, customers))
        ones = np.ones((horizon, 1, customers))
        return cls(zeros, zeros, ones, ones)


def systematic_factor(seed: int, horizon: int, paths: int, assumptions: Assumptions) -> np.ndarray:
    """Common income factor per path and month, (H, P): exp(sigma z - sigma^2/2) with AR(1) z."""
    sigma, phi = assumptions.systematic_income_volatility, assumptions.systematic_persistence
    eps = np.random.default_rng(np.random.SeedSequence([seed, 0])).standard_normal((horizon, paths))
    z = np.zeros((horizon, paths))
    for t in range(horizon):
        z[t] = (phi * z[t - 1] if t > 0 else 0) + np.sqrt(1 - phi ** 2) * eps[t]
    return np.exp(sigma * z - sigma ** 2 / 2)


@dataclass
class LoanSchedule:
    """Deterministic loan schedule of a chunk: index t = after month t (0 = start)."""

    installment: np.ndarray  # (H+2, c): installments due in month t (t = 1..H+1)
    personal: np.ndarray  # (H+1, c): outstanding principal of non-mortgage loans
    mortgage: np.ndarray  # (H+1, c)
    active: np.ndarray  # (H+1, c): number of active loans

    @classmethod
    def of(cls, start: PortfolioStart, horizon: int) -> LoanSchedule:
        remaining = start.loan_remaining.astype(float).copy()
        outstanding = start.loan_outstanding.copy()
        c = start.size
        installment = np.zeros((horizon + 2, c))
        personal = np.zeros((horizon + 1, c))
        mortgage = np.zeros((horizon + 1, c))
        active = np.zeros((horizon + 1, c))
        personal[0] = (outstanding * ~start.loan_mortgage).sum(1)
        mortgage[0] = (outstanding * start.loan_mortgage).sum(1)
        active[0] = (remaining > 0).sum(1)
        for t in range(1, horizon + 2):
            due = remaining > 0
            installment[t] = (start.loan_installment * due).sum(1)
            if t > horizon:
                break
            principal = np.where(due, outstanding / np.maximum(remaining, 1), 0)
            outstanding = np.maximum(outstanding - principal, 0)
            remaining = remaining - due
            personal[t] = (outstanding * ~start.loan_mortgage).sum(1)
            mortgage[t] = (outstanding * start.loan_mortgage).sum(1)
            active[t] = (remaining > 0).sum(1)
        return cls(installment, personal, mortgage, active)


class Projection:
    """Projected financial state of one chunk of customers over all paths (arrays (P, c))."""

    def __init__(self, start: PortfolioStart, path: MacroPath, assumptions: Assumptions, paths: int,
                 category_scale: np.ndarray | None = None, extra_saving: float = 0.0) -> None:
        """``category_scale`` (K,) multiplies spending per category (e.g. 0.5 = half the restaurant spending);
        ``extra_saving`` is set aside every month on top (nominal TRY), as in a savings plan."""
        self.s = start
        self.category_scale = np.ones(len(CATEGORIES)) if category_scale is None else np.asarray(category_scale)
        self.extra_saving = extra_saving
        self.path = path
        self.a = assumptions
        self.loans = LoanSchedule.of(start, path.horizon)
        shape = (paths, start.size)
        p, c = shape

        def tile(values: np.ndarray) -> np.ndarray:
            return np.broadcast_to(values, shape).astype(float).copy()

        self.income_level = np.median(start.income_history, axis=1)
        self.income_history = np.broadcast_to(start.income_history.T[:, None, :], (3, p, c)).copy()
        self.expense_history = np.broadcast_to(start.expense0, (3, p, c)).copy()
        older = np.maximum(start.spend90 - start.spend30, 0) / 2
        self.spend_history = np.stack([tile(older), tile(older), tile(start.spend30)])
        self.category_history = np.broadcast_to(start.category_base.astype(np.float32),
                                                (3, p, c, len(CATEGORIES))).copy()
        self.liquidity = tile(start.liquidity0)
        self.usd = tile(start.usd0)
        self.borrowed = np.zeros(shape)
        self.limit = tile(start.card_limit0)
        self.blocked = np.zeros(shape, dtype=bool)
        self.late_count = tile(start.late_count0)
        self.days_since_late = tile(start.days_since_late0)
        self.missed = np.zeros(shape, dtype=bool)
        spend0 = start.category_base.sum(1)
        interest0 = start.card_debt0 * assumptions.revolving_share * path.card_rate[0]
        self.other0 = np.maximum(start.expense0 - spend0 - self.loans.installment[1] - interest0, 0)
        self.income_sigma = np.clip(np.nan_to_num(start.income_cv, nan=0.05) * assumptions.income_volatility_scale,
                                    0.0, 0.6)
        self.wage = np.stack([path.wage_index[SEGMENTS[i]] if i >= 0 else path.wage_index[SEGMENTS[0]]
                              for i in start.segment_index], axis=1)  # (H+1, c)
        self.deposit_share = np.nan_to_num(start.deposit_share0, nan=0.0)
        # Outflows revert towards the segment's expense-to-income ratio (90-day averages contain transient items).
        ratio0, target = start.expense_ratio0, start.expense_ratio_target
        months = np.arange(path.horizon + 1)[:, None]
        reverted = target + (ratio0 - target) * assumptions.expense_reversion_6m ** (months / 6)
        valid = np.isfinite(ratio0) & np.isfinite(target) & (ratio0 > 0)
        self.expense_level = np.where(valid, np.clip(reverted / np.where(valid, ratio0, 1), 0.2, 5.0), 1.0)
        self.t = 0
        observed = to_matrix(start.features0).to_numpy(dtype=np.float32)[:, _ANCHORED_COLUMNS]
        recomputed = self.raw_matrix()[:c, _ANCHORED_COLUMNS]  # all paths are equal at the start
        self.anchor = np.where(np.isfinite(observed) & np.isfinite(recomputed), observed - recomputed, 0.0)

    def card_debt(self, t: int | None = None) -> np.ndarray:
        t = self.t if t is None else t
        return self.s.card_debt0 * self.path.price_index[t] + self.borrowed

    def exposure(self) -> tuple[np.ndarray, np.ndarray]:
        """(EAD, loss given default) of every (path, customer) in the current state."""
        a, s, t = self.a, self.s, self.t
        debt = self.card_debt()
        undrawn = np.where(s.has_card > 0, np.maximum(self.limit - debt, 0), 0)
        unsecured = debt + a.card_ccf * undrawn + self.loans.personal[t]
        ead = unsecured + self.loans.mortgage[t]
        return ead, a.lgd_unsecured * unsecured + a.lgd_mortgage * self.loans.mortgage[t]

    def step(self, systematic: np.ndarray, income_eps: np.ndarray, expense_eps: np.ndarray) -> None:
        """Projects month t + 1. ``systematic`` (P,), shocks (P, c)."""
        a, s, path = self.a, self.s, self.path
        t = self.t + 1
        price = path.price_index[t]
        fx = path.usd_try[t] / path.usd_try[0]
        sys = systematic[:, None]

        income = self.income_level * self.wage[t] * sys
        sigma = self.income_sigma
        income = income * np.exp(sigma * income_eps - sigma ** 2 / 2)
        income = np.where(s.has_income, income, 0.0)

        real_income = self.wage[t] * sys / price
        category_price = np.where(_FX, (1 - a.fx_pass_through) * price + a.fx_pass_through * fx, price)
        elastic = np.power(np.maximum(real_income, 1e-6), a.discretionary_elasticity)  # (P, c)
        noise = np.exp(a.expense_volatility * expense_eps - a.expense_volatility ** 2 / 2) * self.expense_level[t]
        categories = (s.category_base[None, :, :] * (category_price * self.category_scale)[None, None, :]
                      * np.where(_DISCRETIONARY[None, None, :], elastic[:, :, None], 1.0) * noise[:, :, None])
        spend = categories.sum(-1)
        other = self.other0 * price * noise
        installment = self.loans.installment[t]

        rate = path.card_rate[t]
        cyclical = s.card_debt0 * path.price_index[t - 1]
        interest = a.revolving_share * cyclical * rate + self.borrowed * rate
        self.borrowed = self.borrowed + interest  # revolving interest is capitalised on the card
        cash_out = spend + other + installment
        deposit_interest = np.maximum(self.liquidity, 0) * self.deposit_share * path.deposit_rate[t] * (
            1 - a.deposit_tax_rate)
        liquidity = self.liquidity + income - cash_out + deposit_interest + self.extra_saving

        # Negative cash flow: liquidity buffer first, then USD savings (revalued), then the card limit.
        shortfall = np.maximum(-liquidity, 0)
        liquidity = np.maximum(liquidity, 0)
        usd_value = self.usd * path.usd_try[t]
        sold = np.minimum(shortfall, usd_value)
        self.usd = self.usd - sold / path.usd_try[t]
        shortfall -= sold
        debt = s.card_debt0 * price + self.borrowed
        available = np.where((s.has_card > 0) & ~self.blocked, np.maximum(self.limit - debt, 0), 0)
        borrow = np.minimum(shortfall, available)
        self.borrowed = self.borrowed + borrow
        shortfall -= borrow
        obligations = installment + s.minimum_ratio * debt
        self.missed = (shortfall > MISS_THRESHOLD) & (obligations > 0)
        self.late_count = self.late_count + self.missed
        self.blocked |= self.missed

        known = self.days_since_late < NEVER_LATE_DAYS
        self.days_since_late = np.where(known, np.minimum(self.days_since_late + DAYS_PER_MONTH, NEVER_LATE_DAYS),
                                        self.days_since_late)
        self.days_since_late = np.where(self.missed, 0.0, self.days_since_late)

        # Excess cash pays down card debt borrowed during the simulation.
        floor = a.liquidity_floor_months * cash_out
        repay = np.clip(liquidity - floor, 0, self.borrowed)
        self.borrowed = self.borrowed - repay
        self.liquidity = liquidity - repay
        self.limit = np.where(self.blocked, self.limit, s.card_limit0 * price)

        self.income_history = np.roll(self.income_history, -1, axis=0)
        self.income_history[2] = income
        self.expense_history = np.roll(self.expense_history, -1, axis=0)
        self.expense_history[2] = spend + other + installment + interest
        self.spend_history = np.roll(self.spend_history, -1, axis=0)
        self.spend_history[2] = spend
        self.category_history = np.roll(self.category_history, -1, axis=0)
        self.category_history[2] = categories
        self.t = t

    def matrix(self) -> np.ndarray:
        """Model matrix (P * c, len(MATRIX_COLUMNS)) of the current state, anchored on the observed start."""
        x = self.raw_matrix()
        p = x.shape[0] // self.s.size
        anchored = x[:, _ANCHORED_COLUMNS] + np.tile(self.anchor, (p, 1))
        x[:, _ANCHORED_COLUMNS] = np.clip(anchored, _ANCHOR_LOW, _ANCHOR_HIGH)
        return x

    def raw_matrix(self) -> np.ndarray:
        """Features recomputed from the projected state (P * c, len(MATRIX_COLUMNS)), float32."""
        s, t = self.s, self.t
        p, c = self.liquidity.shape
        x = np.empty((p, c, len(MATRIX_COLUMNS)), dtype=np.float32)

        def put(name: str, values: np.ndarray | float) -> None:
            x[:, :, _COLUMN[name]] = values

        income = np.median(self.income_history, axis=0)
        income = np.where(income > 0, income, np.nan)
        expense = self.expense_history.mean(axis=0)
        debt = self.card_debt()
        put("utilization_rate", np.where(self.limit > 0, debt / np.where(self.limit > 0, self.limit, 1), np.nan))
        put("debt_to_income", (self.loans.installment[t + 1] + s.minimum_ratio * debt) / income)
        put("card_debt_to_income", debt / income)
        put("total_debt_to_income", (debt + self.loans.personal[t] + self.loans.mortgage[t]) / income)
        put("expense_to_income", expense / income)
        put("liquidity_buffer_months",
            np.where(expense > 0, np.minimum(self.liquidity / np.where(expense > 0, expense, 1), 60.0), np.nan))
        put("deposit_share", np.where(self.liquidity > 0, s.deposit_share0, np.nan))
        spend3 = self.spend_history.sum(axis=0)
        put("spend_trend", np.where(spend3 > 0, self.spend_history[2] / np.where(spend3 > 0, spend3, 1) * 3 - 1,
                                    np.nan))
        put("income_cv", s.income_cv)
        put("salary_day_deviation", s.salary_day_deviation)
        put("no_income_observed", s.no_income_observed)
        put("late_payment_count", self.late_count)
        put("days_since_last_late", self.days_since_late)
        put("active_loans", self.loans.active[t])
        put("has_card", s.has_card)
        put("usd_holder", s.usd_holder)
        categories = self.category_history.sum(axis=0)
        total = categories.sum(axis=-1)
        safe_total = np.where(total > 0, total, 1)
        for name, index in _SHARE_INDEX.items():
            put(name, np.where(total > 0, categories[:, :, index].sum(axis=-1) / safe_total, np.nan))
        for k, segment in enumerate(SEGMENTS):
            put(f"segment_{segment}", (s.segment_index == k).astype(np.float32))
        return x.reshape(p * c, len(MATRIX_COLUMNS))

    def balances(self) -> tuple[np.ndarray, np.ndarray]:
        """(liquid balance incl. USD at the current rate, liquidity buffer in months), each (P, c)."""
        expense = self.expense_history.mean(axis=0)
        buffer = np.where(expense > 0, np.minimum(self.liquidity / np.where(expense > 0, expense, 1), 60.0), 0.0)
        return self.liquidity + self.usd * self.path.usd_try[self.t], buffer


@dataclass
class RunAccumulator:
    """Results of one scenario run, summed over customer chunks."""

    paths: int
    horizon: int
    customers: int
    defaults: np.ndarray = field(init=False)  # (P, H)
    churns: np.ndarray = field(init=False)
    alive_credit: np.ndarray = field(init=False)  # credit customers active at the start of month t
    alive: np.ndarray = field(init=False)
    loss: np.ndarray = field(init=False)  # (P,)
    segment_defaults: np.ndarray = field(init=False)  # (P, S)
    segment_churns: np.ndarray = field(init=False)
    segment_loss: np.ndarray = field(init=False)
    migration: np.ndarray = field(init=False)  # (10, 12): start grade -> end grade / DEFAULT / CHURN
    feature_sum: np.ndarray = field(init=False)  # (H+1, F) over active (path, customer) pairs
    feature_count: np.ndarray = field(init=False)
    pd_sum: np.ndarray = field(init=False)  # (H+1,) mean PD of active credit customers
    pd_count: np.ndarray = field(init=False)
    per_customer: dict[str, np.ndarray] = field(init=False)
    forecasts: np.ndarray | None = None  # (C, H, 7)

    def __post_init__(self) -> None:
        p, h, s, c = self.paths, self.horizon, len(SEGMENTS), self.customers
        self.defaults = np.zeros((p, h))
        self.churns = np.zeros((p, h))
        self.alive_credit = np.zeros((p, h))
        self.alive = np.zeros((p, h))
        self.loss = np.zeros(p)
        self.segment_defaults = np.zeros((p, s))
        self.segment_churns = np.zeros((p, s))
        self.segment_loss = np.zeros((p, s))
        self.migration = np.zeros((10, 12))
        self.feature_sum = np.zeros((h + 1, len(TRACKED)))
        self.feature_count = np.zeros((h + 1, len(TRACKED)))
        self.pd_sum = np.zeros(h + 1)
        self.pd_count = np.zeros(h + 1)
        self.per_customer = {name: np.zeros(c) for name in (
            "pd_start", "churn_start", "pd_horizon", "churn_horizon", "default_probability", "churn_probability",
            "ead_start")}


def _track(acc: RunAccumulator, t: int, x: np.ndarray, active: np.ndarray, pd: np.ndarray,
           credit: np.ndarray) -> None:
    rows = active.reshape(-1)
    for j, name in enumerate(TRACKED):
        values = x[rows, _COLUMN[name]].astype(float)
        if name == "liquidity_buffer_months":
            values = np.minimum(values, 60.0)
        ok = np.isfinite(values)
        acc.feature_sum[t, j] += values[ok].sum()
        acc.feature_count[t, j] += ok.sum()
    credit_active = active & credit[None, :]
    acc.pd_sum[t] += pd[credit_active].sum()
    acc.pd_count[t] += credit_active.sum()


def logit(p: np.ndarray) -> np.ndarray:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return np.log(p / (1 - p))


def sigmoid(z: np.ndarray) -> np.ndarray:
    return 1 / (1 + np.exp(-z))


def run_scenarios(start: PortfolioStart, paths: list[MacroPath], reference: MacroPath, assumptions: Assumptions,
                  n_paths: int, seed: int, pd_model: RiskModelArtifact, churn_model: RiskModelArtifact,
                  keep_forecasts: list[bool], progress: Callable[[float], None] | None = None
                  ) -> list[RunAccumulator]:
    """Runs one or more scenarios (same horizon, same random numbers) against the neutral reference path."""
    horizon = paths[0].horizon
    if any(p.horizon != horizon for p in paths) or reference.horizon != horizon:
        raise ValueError("scenarios and reference must have the same horizon")
    accumulators = []
    for keep in keep_forecasts:
        acc = RunAccumulator(n_paths, horizon, start.size)
        if keep:
            acc.forecasts = np.zeros((start.size, horizon, 7), dtype=np.float32)
        accumulators.append(acc)
    systematic = systematic_factor(seed, horizon, n_paths, assumptions)
    size = max(1, chunk_rows() // n_paths)
    chunks = [np.arange(i, min(i + size, start.size)) for i in range(0, start.size, size)]
    for n, index in enumerate(chunks):
        chunk = start.subset(index)
        draws = Draws.for_customers(seed, chunk.customer_id, horizon, n_paths)
        x0 = to_matrix(chunk.features0).to_numpy(dtype=np.float32)
        start_scores = (np.where(chunk.credit, predict(pd_model, x0), 0.0), predict(churn_model, x0))
        reference_scores = _reference_scores(chunk, reference, assumptions, n_paths, draws, pd_model, churn_model,
                                             start_scores)
        for acc, path in zip(accumulators, paths, strict=True):
            _run_chunk(acc, chunk, index, path, assumptions, systematic, draws, pd_model, churn_model, x0,
                       start_scores, reference_scores)
        if progress is not None:
            progress((n + 1) / len(chunks))
    return accumulators


def _reference_scores(chunk: PortfolioStart, reference: MacroPath, assumptions: Assumptions, n_paths: int,
                      draws: Draws, pd_model: RiskModelArtifact, churn_model: RiskModelArtifact,
                      start_scores: tuple[np.ndarray, np.ndarray]) -> tuple[np.ndarray, np.ndarray]:
    """Log-odds of PD and churn on the neutral reference projection after months 0..H, (H+1, P, c).

    The reference uses the same idiosyncratic shocks but no systematic factor, so their effect (already priced into
    the point-in-time PD at the start) cancels in the scenario-minus-reference difference.
    """
    p, c, h = n_paths, chunk.size, reference.horizon
    projection = Projection(chunk, reference, assumptions, p)
    pd_logit = np.empty((h + 1, p, c))
    churn_logit = np.empty((h + 1, p, c))
    pd_logit[0] = logit(start_scores[0])
    churn_logit[0] = logit(start_scores[1])
    ones = np.ones(p)
    for t in range(1, h + 1):
        projection.step(ones, draws.income[t - 1], draws.expense[t - 1])
        x = projection.matrix()
        pd_logit[t] = predict_logit(pd_model, x, p, chunk.credit)
        churn_logit[t] = predict_logit(churn_model, x, p)
    return pd_logit, churn_logit


def adjusted(start_probability: np.ndarray, scenario_logit: np.ndarray, reference_logit: np.ndarray) -> np.ndarray:
    """Start probability shifted by the scenario's log-odds change against the reference projection."""
    return sigmoid(logit(start_probability) + scenario_logit - reference_logit)


def _run_chunk(acc: RunAccumulator, chunk: PortfolioStart, index: np.ndarray, path: MacroPath,
               assumptions: Assumptions, systematic: np.ndarray, draws: Draws, pd_model: RiskModelArtifact,
               churn_model: RiskModelArtifact, x0: np.ndarray, start_scores: tuple[np.ndarray, np.ndarray],
               reference: tuple[np.ndarray, np.ndarray]) -> None:
    p, h, c = acc.paths, acc.horizon, chunk.size
    projection = Projection(chunk, path, assumptions, p)
    credit = chunk.credit
    segment = chunk.segment_index
    alive = np.ones((p, c), dtype=bool)
    defaulted = np.zeros((p, c), dtype=bool)
    churned = np.zeros((p, c), dtype=bool)
    ead0, _ = projection.exposure()
    acc.per_customer["ead_start"][index] = np.where(credit, ead0[0], 0)
    pd_start, churn_start = start_scores
    acc.per_customer["pd_start"][index] = pd_start
    acc.per_customer["churn_start"][index] = churn_start
    start_grade = grade_of(pd_start)
    x = np.broadcast_to(x0, (p, c, x0.shape[1])).reshape(p * c, -1)

    def scores(t: int, x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        pd6 = adjusted(pd_start, predict_logit(pd_model, x, p, credit), reference[0][t])
        churn6 = adjusted(churn_start, predict_logit(churn_model, x, p), reference[1][t])
        return np.where(credit, pd6, 0.0), churn6

    for t in range(1, h + 1):
        if t == 1:
            pd6, churn6 = np.broadcast_to(pd_start, (p, c)), np.broadcast_to(churn_start, (p, c))
        else:
            x = projection.matrix()
            pd6, churn6 = scores(t - 1, x)
        _track(acc, t - 1, x, alive, pd6, credit)

        credit_alive = alive & credit
        new_default = credit_alive & (draws.default[t - 1] < monthly_hazard(pd6))
        new_churn = alive & ~new_default & (draws.churn[t - 1] < monthly_hazard(churn6))
        _, loss = projection.exposure()
        loss = np.where(new_default, loss, 0.0)
        acc.alive_credit[:, t - 1] += credit_alive.sum(1)
        acc.alive[:, t - 1] += alive.sum(1)
        acc.defaults[:, t - 1] += new_default.sum(1)
        acc.churns[:, t - 1] += new_churn.sum(1)
        acc.loss += loss.sum(1)
        for k in range(len(SEGMENTS)):
            in_segment = segment == k
            acc.segment_defaults[:, k] += new_default[:, in_segment].sum(1)
            acc.segment_churns[:, k] += new_churn[:, in_segment].sum(1)
            acc.segment_loss[:, k] += loss[:, in_segment].sum(1)
        defaulted |= new_default
        churned |= new_churn
        alive &= ~(new_default | new_churn)

        projection.step(systematic[t - 1], draws.income[t - 1], draws.expense[t - 1])
        if acc.forecasts is not None:
            balance, buffer = projection.balances()
            bands = np.quantile(np.stack([balance, buffer]), [0.1, 0.5, 0.9], axis=1)  # (3, 2, c)
            acc.forecasts[index, t - 1, 0:3] = bands[:, 0, :].T
            acc.forecasts[index, t - 1, 3:6] = bands[:, 1, :].T
            acc.forecasts[index, t - 1, 6] = defaulted.mean(0)

    x = projection.matrix()
    pd_end, churn_end = scores(h, x)
    _track(acc, h, x, alive, pd_end, credit)
    acc.per_customer["pd_horizon"][index] = pd_end.mean(0)
    acc.per_customer["churn_horizon"][index] = churn_end.mean(0)
    acc.per_customer["default_probability"][index] = defaulted.mean(0)
    acc.per_customer["churn_probability"][index] = churned.mean(0)

    end = np.where(defaulted, 10, np.where(churned, 11, grade_of(pd_end) - 1))
    rows = np.broadcast_to(start_grade - 1, (p, c))
    np.add.at(acc.migration, (rows[:, credit].ravel(), end[:, credit].ravel()), 1)


def central_features(start: PortfolioStart, path: MacroPath, assumptions: Assumptions) -> np.ndarray:
    """Features at the horizon on the central path (no shocks, no events), (C, len(MATRIX_COLUMNS))."""
    projection = Projection(start, path, assumptions, 1)
    draws = Draws.zero(path.horizon, start.size)
    flat = np.ones((path.horizon, 1))
    for t in range(path.horizon):
        projection.step(flat[t], draws.income[t], draws.expense[t])
    return projection.matrix()

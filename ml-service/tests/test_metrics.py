from __future__ import annotations

import numpy as np
import pytest

from app.grades import GRADE_BOUNDS, grade, grade_ranges
from app.training.metrics import calibration_table, discrimination, psi


def test_perfect_and_random_discrimination() -> None:
    y = np.array([0, 0, 0, 1, 1])
    perfect = discrimination(y, np.array([0.1, 0.2, 0.3, 0.8, 0.9]))
    assert perfect["auc"] == 1.0 and perfect["gini"] == 1.0 and perfect["ks"] == 1.0
    rng = np.random.default_rng(1)
    y = rng.integers(0, 2, 20_000)
    random = discrimination(y, rng.random(20_000))
    assert random["auc"] == pytest.approx(0.5, abs=0.02)
    assert random["ks"] < 0.05


def test_brier_and_undefined_auc() -> None:
    result = discrimination(np.array([0, 0, 0]), np.array([0.1, 0.1, 0.1]))
    assert result["auc"] is None
    assert result["brier"] == pytest.approx(0.01)


def test_calibration_table_of_a_calibrated_model() -> None:
    rng = np.random.default_rng(2)
    p = rng.uniform(0, 0.3, 100_000)
    y = rng.random(100_000) < p
    table = calibration_table(y.astype(int), p)
    assert len(table) == 10
    for row in table:
        assert row["observed_rate"] == pytest.approx(row["mean_prediction"], abs=0.01)


def test_psi_detects_shift() -> None:
    rng = np.random.default_rng(3)
    base = rng.normal(0, 1, 50_000)
    assert psi(base, rng.normal(0, 1, 50_000)) < 0.01
    assert psi(base, rng.normal(1, 1, 50_000)) > 0.25


def test_master_scale() -> None:
    assert grade(0.0) == 1
    assert grade(0.0009) == 1
    assert grade(0.001) == 2
    assert grade(0.019) == 5
    assert grade(0.5) == 10
    assert [grade(b) for b in GRADE_BOUNDS] == list(range(2, 11))
    assert len(grade_ranges()) == 10

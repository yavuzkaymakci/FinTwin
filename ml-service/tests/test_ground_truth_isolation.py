"""ground_truth (the generator's hidden variables) must never reach PD/churn training or scoring.

Only ``app/labeling`` (label export, the stand-in for a hand-labelled dataset) and ``app/evaluation`` (offline
analysis) may touch it, and nothing else may import them. The database role ``fintwin_ml`` cannot read it at all.
"""

from __future__ import annotations

import ast
from pathlib import Path

import psycopg
import pytest

from app.db import connect

APP = Path(__file__).resolve().parents[1] / "app"
ALLOWED = {APP / "labeling", APP / "evaluation"}


def _sources() -> list[Path]:
    return [p for p in APP.rglob("*.py") if not any(a in p.parents for a in ALLOWED)]


def test_only_labeling_and_evaluation_mention_ground_truth() -> None:
    offenders = [str(p.relative_to(APP)) for p in _sources() if "ground_truth" in p.read_text(encoding="utf-8")]
    assert offenders == []


def test_nothing_else_imports_labeling_or_evaluation() -> None:
    offenders = []
    for path in _sources():
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            modules = []
            if isinstance(node, ast.Import):
                modules = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                modules = [node.module]
            offenders += [f"{path.relative_to(APP)}: {m}" for m in modules
                          if m.startswith(("app.labeling", "app.evaluation"))]
    assert offenders == []


@pytest.mark.usefixtures("database")
def test_training_role_cannot_read_ground_truth() -> None:
    with connect() as conn, pytest.raises(psycopg.errors.InsufficientPrivilege):
        conn.execute("SELECT COUNT(*) FROM ground_truth.customer_monthly_state")


@pytest.mark.usefixtures("database")
def test_training_role_is_read_only() -> None:
    with connect() as conn, pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
        conn.execute("DELETE FROM twin.customer_risk_score")

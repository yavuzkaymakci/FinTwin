"""Out-of-time split by observation month: train, then validation, then test (strictly later)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import pandas as pd


@dataclass(frozen=True)
class Period:
    name: str
    start: date
    end: date  # inclusive observation month

    def describe(self) -> str:
        return f"{self.start:%Y-%m} .. {self.end:%Y-%m}"


def time_split(frame: pd.DataFrame, validation_months: int = 4, test_months: int = 6
               ) -> tuple[dict[str, pd.DataFrame], list[Period]]:
    """Splits by ``as_of``: the last ``test_months`` observation months are the test period, the
    ``validation_months`` before them validation, everything earlier training."""
    months = sorted(frame["as_of"].unique())
    if len(months) < validation_months + test_months + 1:
        raise ValueError(f"Need more than {validation_months + test_months} observation months, got {len(months)}")
    train_months = months[: len(months) - validation_months - test_months]
    val_months = months[len(train_months): len(train_months) + validation_months]
    test_months_list = months[len(train_months) + validation_months:]
    periods = [Period("train", train_months[0], train_months[-1]),
               Period("validation", val_months[0], val_months[-1]),
               Period("test", test_months_list[0], test_months_list[-1])]
    parts = {p.name: frame[(frame["as_of"] >= p.start) & (frame["as_of"] <= p.end)].copy() for p in periods}
    return parts, periods

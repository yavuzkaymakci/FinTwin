"""Design matrix shared by training and scoring: numeric features plus one-hot encoded segment."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from typing import Any

import numpy as np
import pandas as pd

from app.features import NUMERIC_FEATURES, SEGMENTS

SEGMENT_COLUMNS = [f"segment_{s}" for s in SEGMENTS]
MATRIX_COLUMNS = [*NUMERIC_FEATURES, *SEGMENT_COLUMNS]


def to_matrix(rows: pd.DataFrame | Iterable[Mapping[str, Any]]) -> pd.DataFrame:
    """Float matrix with :data:`MATRIX_COLUMNS` (NaN = missing)."""
    frame = rows if isinstance(rows, pd.DataFrame) else pd.DataFrame(list(rows))
    matrix = pd.DataFrame(index=frame.index)
    for column in NUMERIC_FEATURES:
        matrix[column] = pd.to_numeric(frame[column], errors="coerce").astype(float) if column in frame \
            else np.nan
    segment = frame["segment"].astype(str) if "segment" in frame else pd.Series("", index=frame.index)
    for s, column in zip(SEGMENTS, SEGMENT_COLUMNS, strict=True):
        matrix[column] = (segment == s).astype(float)
    return matrix[MATRIX_COLUMNS]


def feature_of(column: str) -> str:
    """Model feature a matrix column belongs to (segment dummies map to ``segment``)."""
    return "segment" if column.startswith("segment_") else column

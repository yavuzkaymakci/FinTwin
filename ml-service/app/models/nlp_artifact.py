"""Serialized transaction categorisation model (TF-IDF features + linear classifier)."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

INPUT_COLUMNS = ["raw_description", "channel", "direction", "account_type"]


def to_frame(items: Sequence[Mapping[str, Any]]) -> pd.DataFrame:
    frame = pd.DataFrame([{c: (item.get(c) or "") for c in INPUT_COLUMNS} for item in items])
    frame["raw_description"] = frame["raw_description"].astype(str).str.upper()
    return frame


@dataclass
class CategorizerArtifact:
    version: str
    pipeline: Any  # sklearn Pipeline: ColumnTransformer -> classifier with predict_proba
    variant: str

    @property
    def classes(self) -> list[str]:
        return [str(c) for c in self.pipeline.classes_]

    def predict(self, items: Sequence[Mapping[str, Any]], top: int = 3) -> list[dict[str, Any]]:
        if not items:
            return []
        proba = self.pipeline.predict_proba(to_frame(items))
        classes = np.array(self.classes)
        results = []
        for row in proba:
            order = np.argsort(-row)[:top]
            results.append({"category": classes[order[0]], "probability": float(row[order[0]]),
                            "alternatives": [{"category": classes[j], "probability": float(row[j])}
                                             for j in order[1:]]})
        return results

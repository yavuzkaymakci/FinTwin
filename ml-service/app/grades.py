"""Master rating scale: maps a calibrated probability to a risk grade 1 (best) .. 10 (worst)."""

from __future__ import annotations

import bisect

#: Lower PD bound of grades 2..10 (grade 1 is below 0.10 %).
GRADE_BOUNDS = [0.001, 0.0025, 0.005, 0.01, 0.02, 0.04, 0.08, 0.15, 0.30]


def grade(probability: float) -> int:
    return 1 + bisect.bisect_right(GRADE_BOUNDS, probability)


def grade_ranges() -> list[dict[str, float | int | None]]:
    lows = [0.0, *GRADE_BOUNDS]
    highs = [*GRADE_BOUNDS, None]
    return [{"grade": i + 1, "pd_from": lo, "pd_to": hi} for i, (lo, hi) in enumerate(zip(lows, highs, strict=True))]

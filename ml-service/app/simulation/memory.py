"""Peak memory measurement of a simulation run."""

from __future__ import annotations

import threading
from typing import Any

import psutil


class PeakMemory:
    """Samples the process RSS in the background; ``peak_mb`` is the maximum seen while running."""

    def __init__(self, interval: float = 0.2) -> None:
        self.interval = interval
        self.peak_mb = 0.0
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def _run(self) -> None:
        process = psutil.Process()
        while not self._stop.is_set():
            self.peak_mb = max(self.peak_mb, process.memory_info().rss / 2 ** 20)
            self._stop.wait(self.interval)

    def __enter__(self) -> PeakMemory:
        self._thread.start()
        return self

    def __exit__(self, *_: Any) -> None:
        self._stop.set()
        self._thread.join()
        self.peak_mb = max(self.peak_mb, psutil.Process().memory_info().rss / 2 ** 20)

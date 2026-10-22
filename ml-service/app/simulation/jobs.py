"""Asynchronous simulation jobs: one worker thread (memory budget), in-memory status and results.

A job is submitted, queued, run in the background and kept in memory until it is evicted (the most recent
``MAX_JOBS`` finished jobs stay available). The backend polls the status and stores the results durably, so a
restart of the ML service only loses jobs the backend has not collected yet.
"""

from __future__ import annotations

import logging
import threading
import time
import uuid
from collections import OrderedDict
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from app.simulation.memory import PeakMemory
from app.simulation.scenario import SimulationRequest

log = logging.getLogger(__name__)

MAX_JOBS = 20


class JobStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


@dataclass
class Job:
    id: str
    request: SimulationRequest
    status: JobStatus = JobStatus.QUEUED
    progress: float = 0.0
    submitted_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    started_at: datetime | None = None
    finished_at: datetime | None = None
    error: str | None = None
    summary: dict[str, Any] | None = None
    forecasts: list[dict[str, Any]] | None = None
    duration_seconds: float | None = None
    peak_rss_mb: float | None = None


#: Runs a request: (request, progress callback) -> {"summary": ..., "forecasts": [...]}.
Runner = Callable[[SimulationRequest, Callable[[float], None]], dict[str, Any]]


class JobManager:
    def __init__(self, runner: Runner) -> None:
        self._runner = runner
        self._jobs: OrderedDict[str, Job] = OrderedDict()
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="simulation")

    def submit(self, request: SimulationRequest) -> Job:
        job = Job(id=str(uuid.uuid4()), request=request)
        with self._lock:
            self._jobs[job.id] = job
            self._evict()
        self._executor.submit(self._run, job)
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def _evict(self) -> None:
        finished = [j.id for j in self._jobs.values() if j.status in (JobStatus.SUCCEEDED, JobStatus.FAILED)]
        for job_id in finished[:max(len(finished) - MAX_JOBS, 0)]:
            del self._jobs[job_id]

    def _run(self, job: Job) -> None:
        job.status = JobStatus.RUNNING
        job.started_at = datetime.now(UTC)
        began = time.perf_counter()

        def progress(done: float) -> None:
            job.progress = round(min(max(done, 0.0), 1.0) * 100, 1)

        try:
            with PeakMemory() as memory:
                result = self._runner(job.request, progress)
            job.summary = result["summary"]
            job.forecasts = result["forecasts"]
            job.peak_rss_mb = round(memory.peak_mb, 1)
            job.progress = 100.0
            job.status = JobStatus.SUCCEEDED
        except Exception as e:  # noqa: BLE001 - any failure is reported on the job
            log.exception("simulation %s failed", job.id)
            job.error = f"{type(e).__name__}: {e}"
            job.status = JobStatus.FAILED
        finally:
            job.finished_at = datetime.now(UTC)
            job.duration_seconds = round(time.perf_counter() - began, 2)
            with self._lock:
                self._evict()

    def shutdown(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)

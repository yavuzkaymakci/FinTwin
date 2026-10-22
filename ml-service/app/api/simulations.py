from __future__ import annotations

from datetime import datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import Field

from app.api.schemas import ApiModel
from app.scoring.service import LoadedModels, get_models
from app.simulation.jobs import Job, JobManager, JobStatus
from app.simulation.scenario import SimulationRequest
from app.simulation.service import get_job_manager

router = APIRouter(prefix="/v1/simulations", tags=["simulations"])


class SimulationJob(ApiModel):
    job_id: str
    status: JobStatus
    progress: float = Field(description="percent done")
    submitted_at: datetime
    started_at: datetime | None = None
    finished_at: datetime | None = None
    error: str | None = None
    duration_seconds: float | None = None
    peak_rss_mb: float | None = Field(None, description="peak resident memory of the ML service during the run")
    result: dict[str, Any] | None = Field(None, description="results without the per-customer forecasts")


class ForecastPage(ApiModel):
    job_id: str
    total: int
    offset: int
    items: list[dict[str, Any]]


def _view(job: Job, with_result: bool = True) -> SimulationJob:
    return SimulationJob(job_id=job.id, status=job.status, progress=job.progress, submitted_at=job.submitted_at,
                         started_at=job.started_at, finished_at=job.finished_at, error=job.error,
                         duration_seconds=job.duration_seconds, peak_rss_mb=job.peak_rss_mb,
                         result=job.summary if with_result else None)


def _job(job_id: str, jobs: JobManager) -> Job:
    job = jobs.get(job_id)
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail=f"Unknown simulation job {job_id}")
    return job


@router.post("", status_code=status.HTTP_202_ACCEPTED, response_model=SimulationJob, response_model_by_alias=True)
def submit(request: SimulationRequest, jobs: Annotated[JobManager, Depends(get_job_manager)],
           models: Annotated[LoadedModels, Depends(get_models)]) -> SimulationJob:
    models.require_risk()  # fail fast (503) instead of queueing a job that cannot run
    return _view(jobs.submit(request), with_result=False)


@router.get("/{job_id}", response_model=SimulationJob, response_model_by_alias=True)
def get(job_id: str, jobs: Annotated[JobManager, Depends(get_job_manager)]) -> SimulationJob:
    return _view(_job(job_id, jobs))


@router.get("/{job_id}/forecasts", response_model=ForecastPage, response_model_by_alias=True)
def forecasts(job_id: str, jobs: Annotated[JobManager, Depends(get_job_manager)],
              offset: Annotated[int, Query(ge=0)] = 0, limit: Annotated[int, Query(ge=1, le=2000)] = 500,
              customer_id: Annotated[int | None, Query(alias="customerId")] = None) -> ForecastPage:
    job = _job(job_id, jobs)
    if job.status is not JobStatus.SUCCEEDED:
        raise HTTPException(status.HTTP_409_CONFLICT, detail=f"Simulation job {job_id} is {job.status}")
    items = job.forecasts or []
    if customer_id is not None:
        items = [f for f in items if f["customerId"] == customer_id]
    return ForecastPage(job_id=job.id, total=len(items), offset=offset, items=items[offset:offset + limit])

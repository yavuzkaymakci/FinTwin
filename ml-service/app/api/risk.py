from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.schemas import RiskBatchRequest, RiskBatchResponse, RiskExplanation, RiskScore, RiskScoreRequest
from app.scoring.risk_scorer import ScoringInput
from app.scoring.service import LoadedModels, get_models

router = APIRouter(prefix="/v1/risk", tags=["risk"])


def _input(item: RiskScoreRequest) -> ScoringInput:
    return ScoringInput(item.customer_id, item.segment, item.features.as_twin_map())


@router.post("/score", response_model=RiskScore, response_model_by_alias=True)
def score(request: RiskScoreRequest, models: Annotated[LoadedModels, Depends(get_models)]) -> RiskScore:
    return RiskScore.model_validate(models.require_risk().score([_input(request)])[0])


@router.post("/score/batch", response_model=RiskBatchResponse, response_model_by_alias=True)
def score_batch(request: RiskBatchRequest, models: Annotated[LoadedModels, Depends(get_models)]
                ) -> RiskBatchResponse:
    results = models.require_risk().score([_input(i) for i in request.items])
    return RiskBatchResponse(scores=[RiskScore.model_validate(r) for r in results])


@router.post("/explain", response_model=RiskExplanation, response_model_by_alias=True)
def explain(request: RiskScoreRequest, models: Annotated[LoadedModels, Depends(get_models)]) -> RiskExplanation:
    return RiskExplanation.model_validate(models.require_risk().score([_input(request)], full=True)[0])

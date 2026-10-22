from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.schemas import CategorizeRequest, CategorizeResponse, CategoryPrediction
from app.scoring.service import LoadedModels, get_models

router = APIRouter(prefix="/v1", tags=["categorisation"])


@router.post("/categorize", response_model=CategorizeResponse, response_model_by_alias=True)
def categorize(request: CategorizeRequest, models: Annotated[LoadedModels, Depends(get_models)]
               ) -> CategorizeResponse:
    model = models.require_categorizer()
    items = [i.model_dump() for i in request.items]
    return CategorizeResponse(predictions=[CategoryPrediction.model_validate(p) for p in model.predict(items)],
                              model_version=model.version)

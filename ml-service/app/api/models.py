from __future__ import annotations

from typing import Annotated, Any

from fastapi import APIRouter, Depends

from app.api.schemas import ActiveModels, ModelInfo
from app.scoring.service import LoadedModels, get_models

router = APIRouter(prefix="/v1/models", tags=["models"])


def _summary(name: str, m: dict[str, Any]) -> dict[str, Any]:
    if name == "nlp":
        variant = m["variants"][m["selected_variant"]]
        return {"variant": m["selected_variant"], "testMacroF1": variant["macro_f1"],
                "heldOutMerchantsMacroF1": variant["held_out_merchants"]["macro_f1"]}
    test = m["metrics"]["lightgbm"]["test"]
    return {"target": m["target"], "periods": m["data"]["periods"], "testAuc": test["auc"], "testGini": test["gini"],
            "testKs": test["ks"], "testBrier": test["brier"],
            "meanPredicted": m["calibration_test"]["mean_predicted"],
            "observedRate": m["calibration_test"]["observed_rate"],
            "features": [f["name"] for f in m["features"]]}


@router.get("/active", response_model=ActiveModels)
def active_models(models: Annotated[LoadedModels, Depends(get_models)]) -> ActiveModels:
    return ActiveModels(models=[ModelInfo(name=name, version=m["version"], trained_at=m.get("trained_at"),
                                          summary=_summary(name, m)) for name, m in models.metadata.items()])

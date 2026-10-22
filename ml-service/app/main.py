from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app import __version__
from app.api.categorize import router as categorize_router
from app.api.health import router as health_router
from app.api.models import router as models_router
from app.api.risk import router as risk_router
from app.scoring.service import ModelUnavailableError


def create_app() -> FastAPI:
    app = FastAPI(
        title="FinTwin ML Service",
        version=__version__,
        description="Behavioural models (spending categorisation, cash-flow forecast, PD, churn) "
        "with SHAP explanations, and the simulation engine.",
    )
    app.include_router(health_router)
    app.include_router(models_router)
    app.include_router(risk_router)
    app.include_router(categorize_router)

    @app.exception_handler(ModelUnavailableError)
    async def model_unavailable(_: Request, exc: ModelUnavailableError) -> JSONResponse:
        return JSONResponse(status_code=503, content={"error": "MODEL_UNAVAILABLE", "detail": str(exc)})

    return app


app = create_app()

from fastapi import FastAPI

from app import __version__
from app.api.health import router as health_router


def create_app() -> FastAPI:
    app = FastAPI(
        title="FinTwin ML Service",
        version=__version__,
        description="Behavioural models (spending categorisation, cash-flow forecast, PD, churn) "
        "with SHAP explanations, and the simulation engine.",
    )
    app.include_router(health_router)
    return app


app = create_app()

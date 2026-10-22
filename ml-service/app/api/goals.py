from __future__ import annotations

from collections.abc import Callable
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.simulation.goals import GoalRequest
from app.simulation.service import get_goal_runner

router = APIRouter(prefix="/v1/goals", tags=["goals"])


@router.post("/simulate")
def simulate(request: GoalRequest,
             runner: Annotated[Callable[[GoalRequest], dict[str, Any]], Depends(get_goal_runner)]) -> dict[str, Any]:
    """Probability of reaching a savings target from the customer's current twin, with optional changes."""
    try:
        return runner(request)
    except LookupError as e:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail=str(e)) from e

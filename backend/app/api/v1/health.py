from typing import Any

from fastapi import APIRouter

from app.core.config import settings
from app.schemas.response import ApiResponse

router = APIRouter()


@router.get("/health", response_model=ApiResponse[dict[str, Any]])
async def health_check() -> ApiResponse[dict[str, Any]]:
    return ApiResponse(
        success=True,
        data={
            "status": "ok",
            "environment": settings.APP_ENV,
        },
        error=None,
    )

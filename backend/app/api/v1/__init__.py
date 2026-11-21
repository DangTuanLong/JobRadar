"""JobRadar Backend API v1 Router Registry."""

from fastapi import APIRouter

from app.api.v1.cv import router as cv_router
from app.api.v1.health import router as health_router
from app.api.v1.jobs import router as jobs_router

api_v1_router = APIRouter(prefix="/api/v1")
api_v1_router.include_router(health_router, tags=["health"])
api_v1_router.include_router(cv_router)
api_v1_router.include_router(jobs_router)

__all__ = ["api_v1_router"]

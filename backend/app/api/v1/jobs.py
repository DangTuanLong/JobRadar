"""Analysis job status polling endpoints."""

from __future__ import annotations

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends

from app.api.v1.cv import get_job_repository
from app.core.exceptions import AppException
from app.core.security import CurrentUser
from app.db.repositories.job_repository import JobRepository
from app.schemas.job import AnalysisJobResponse
from app.schemas.response import ApiResponse

router = APIRouter(prefix="/jobs", tags=["jobs"])

JobRepoDep = Annotated[JobRepository, Depends(get_job_repository)]


@router.get("/{job_id}", response_model=ApiResponse[AnalysisJobResponse])
async def get_analysis_job(
    job_id: str,
    current_user: CurrentUser,
    job_repo: JobRepoDep,
) -> ApiResponse[AnalysisJobResponse]:
    """Return progress and status for an analysis job owned by the authenticated user."""
    try:
        UUID(job_id)
    except ValueError as exc:
        raise AppException(
            status_code=404,
            code="JOB_NOT_FOUND",
            message="Analysis job not found.",
        ) from exc

    job = await job_repo.get_job_by_id(job_id=job_id, user_id=current_user.user_id)
    if job is None:
        raise AppException(
            status_code=404,
            code="JOB_NOT_FOUND",
            message="Analysis job not found.",
        )

    return ApiResponse(
        success=True,
        data=AnalysisJobResponse.model_validate(job),
        error=None,
    )

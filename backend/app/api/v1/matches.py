"""Company match result querying and matching rerun trigger endpoints."""

from __future__ import annotations

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, BackgroundTasks, Depends, Query, status

from app.api.v1.cv import get_job_repository, get_profile_repository
from app.core.exceptions import AppException
from app.core.security import CurrentUser
from app.db.repositories.job_repository import JobRepository
from app.db.repositories.match_repository import MatchRepository
from app.db.repositories.profile_repository import ProfileRepository
from app.schemas.job import AnalysisJobResponse
from app.schemas.match import MatchResultResponse
from app.schemas.response import ApiResponse
from app.services.matching_service import MatchingService, run_matching_rerun_pipeline
from app.services.profile_service import PROFILE_NOT_FOUND_MESSAGE

router = APIRouter(prefix="/matches", tags=["matches"])


def get_match_repository() -> MatchRepository:
    """Provide MatchRepository dependency."""
    return MatchRepository()


def get_matching_service(
    profile_repo: Annotated[ProfileRepository, Depends(get_profile_repository)],
    match_repo: Annotated[MatchRepository, Depends(get_match_repository)],
) -> MatchingService:
    """Provide MatchingService dependency."""
    return MatchingService(profile_repo=profile_repo, match_repo=match_repo)


JobRepoDep = Annotated[JobRepository, Depends(get_job_repository)]
ProfileRepoDep = Annotated[ProfileRepository, Depends(get_profile_repository)]
MatchRepoDep = Annotated[MatchRepository, Depends(get_match_repository)]
MatchingServiceDep = Annotated[MatchingService, Depends(get_matching_service)]


@router.post(
    "/rerun",
    status_code=status.HTTP_202_ACCEPTED,
    response_model=ApiResponse[AnalysisJobResponse],
)
async def rerun_matching(
    background_tasks: BackgroundTasks,
    current_user: CurrentUser,
    job_repo: JobRepoDep,
    profile_repo: ProfileRepoDep,
    match_repo: MatchRepoDep,
    matching_service: MatchingServiceDep,
) -> ApiResponse[AnalysisJobResponse]:
    """Create a new PENDING analysis job and re-run LangGraph matching in the background."""
    profile = await profile_repo.get_latest_profile(current_user.user_id)
    if profile is None:
        raise AppException(
            status_code=404,
            code="PROFILE_NOT_FOUND",
            message=PROFILE_NOT_FOUND_MESSAGE,
        )

    profile_id = str(profile["id"])
    job = await job_repo.create_job(user_id=current_user.user_id, profile_id=profile_id)
    job_id = str(job["id"])

    background_tasks.add_task(
        run_matching_rerun_pipeline,
        job_id=job_id,
        profile_id=profile_id,
        user_id=current_user.user_id,
        job_repo=job_repo,
        profile_repo=profile_repo,
        match_repo=match_repo,
        matching_service=matching_service,
    )

    return ApiResponse(
        success=True,
        data=AnalysisJobResponse.model_validate(job),
        error=None,
    )


@router.get("", response_model=ApiResponse[list[MatchResultResponse]])
async def get_matches(
    job_id: Annotated[str, Query(description="UUID of the completed analysis job")],
    current_user: CurrentUser,
    job_repo: JobRepoDep,
    match_repo: MatchRepoDep,
) -> ApiResponse[list[MatchResultResponse]]:
    """Return match results for an owned job sorted by match_score descending."""
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

    rows = await match_repo.get_match_results_by_job(job_id=job_id, user_id=current_user.user_id)
    results = [MatchResultResponse.model_validate(row) for row in rows]
    results.sort(key=lambda item: item.match_score, reverse=True)
    return ApiResponse(success=True, data=results, error=None)

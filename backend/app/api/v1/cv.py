"""CV upload and asynchronous analysis trigger endpoints."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, BackgroundTasks, Depends, File, UploadFile, status

from app.core.constants import MAX_CV_FILE_SIZE_BYTES, MIME_PDF
from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.core.security import CurrentUser
from app.db.repositories.job_repository import JobRepository
from app.db.repositories.profile_repository import ProfileRepository
from app.db.supabase_client import upload_cv_to_storage
from app.schemas.job import AnalysisJobResponse
from app.schemas.response import ApiResponse
from app.services.analysis_pipeline_service import MatcherCallback, run_analysis_pipeline
from app.services.cv_parser_service import validate_cv_file

logger = get_logger(__name__)

router = APIRouter(prefix="/cv", tags=["cv"])


def get_job_repository() -> JobRepository:
    """Provide JobRepository dependency."""
    return JobRepository()


def get_profile_repository() -> ProfileRepository:
    """Provide ProfileRepository dependency."""
    return ProfileRepository()


def get_matcher_callback() -> MatcherCallback | None:
    """Provide optional Phase 3 LangGraph matcher hook (None in Phase 2)."""
    return None


JobRepoDep = Annotated[JobRepository, Depends(get_job_repository)]
ProfileRepoDep = Annotated[ProfileRepository, Depends(get_profile_repository)]
MatcherCallbackDep = Annotated[MatcherCallback | None, Depends(get_matcher_callback)]


@router.post(
    "/analyze",
    status_code=status.HTTP_202_ACCEPTED,
    response_model=ApiResponse[AnalysisJobResponse],
)
async def analyze_cv(
    background_tasks: BackgroundTasks,
    current_user: CurrentUser,
    job_repo: JobRepoDep,
    profile_repo: ProfileRepoDep,
    matcher_callback: MatcherCallbackDep,
    cv_file: Annotated[UploadFile, File(description="Candidate CV (PDF, PNG, or JPEG, max 5MB)")],
    linkedin_pdf: Annotated[
        UploadFile | None,
        File(description="Optional LinkedIn exported PDF (max 5MB)"),
    ] = None,
) -> ApiResponse[AnalysisJobResponse]:
    """Validate CV upload, create a PENDING job, and launch background analysis."""
    cv_bytes = await cv_file.read(MAX_CV_FILE_SIZE_BYTES + 1)
    cv_mime = validate_cv_file(cv_bytes, cv_file.content_type)

    linkedin_bytes: bytes | None = None
    linkedin_mime: str | None = None
    if linkedin_pdf is not None:
        linkedin_bytes = await linkedin_pdf.read(MAX_CV_FILE_SIZE_BYTES + 1)
        linkedin_mime = validate_cv_file(
            linkedin_bytes,
            linkedin_pdf.content_type or MIME_PDF,
            allowed_mime_types=frozenset({MIME_PDF}),
        )

    job = await job_repo.create_job(user_id=current_user.user_id)
    job_id = str(job["id"])

    cv_file_url: str | None = None
    try:
        cv_file_url = await upload_cv_to_storage(
            user_id=current_user.user_id,
            job_id=job_id,
            filename=cv_file.filename,
            file_bytes=cv_bytes,
            content_type=cv_mime,
        )
    except AppException as exc:
        logger.warning(
            "Storage upload failed for job_id=%s [%s]: %s. Continuing with in-memory bytes.",
            job_id,
            exc.code,
            exc.message,
        )
    except Exception as exc:
        logger.warning(
            "Unexpected storage upload error for job_id=%s: %s. Continuing with in-memory bytes.",
            job_id,
            exc,
        )

    background_tasks.add_task(
        run_analysis_pipeline,
        job_id=job_id,
        user_id=current_user.user_id,
        cv_bytes=cv_bytes,
        cv_content_type=cv_mime,
        linkedin_pdf_bytes=linkedin_bytes,
        linkedin_content_type=linkedin_mime,
        cv_file_url=cv_file_url,
        job_repo=job_repo,
        profile_repo=profile_repo,
        matcher_callback=matcher_callback,
    )

    return ApiResponse(
        success=True,
        data=AnalysisJobResponse.model_validate(job),
        error=None,
    )

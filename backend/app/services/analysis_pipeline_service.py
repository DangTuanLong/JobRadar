"""Asynchronous background pipeline orchestrating CV parsing, enrichment, and persistence."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

import httpx
from google import genai

from app.core.constants import (
    EMBEDDING_TASK_QUERY,
    ENRICHMENT_SOURCE_TIMEOUT_SECONDS,
    JOB_PROGRESS_COMPLETED,
    JOB_PROGRESS_EMBEDDING,
    JOB_PROGRESS_ENRICHING,
    JOB_PROGRESS_EXTRACTING,
    JOB_PROGRESS_MATCHING,
    JOB_PROGRESS_PENDING,
    JobStatus,
)
from app.core.exceptions import AppException
from app.core.logging import get_logger, reset_job_id, set_job_id
from app.db.repositories.job_repository import JobRepository
from app.db.repositories.profile_repository import ProfileRepository
from app.services.cv_parser_service import parse_cv_document
from app.services.embedding_service import build_profile_embedding_text, generate_embedding
from app.services.enrichment.cache import TTLCache
from app.services.enrichment.enrichment_service import enrich_profile
from app.services.matching_service import MatchingService

logger = get_logger(__name__)

MatcherCallback = Callable[[str, str, str], Awaitable[None]]


async def _safe_mark_failed(
    job_repo: JobRepository,
    job_id: str,
    progress_percent: int,
    error_code: str,
    error_message: str,
) -> None:
    """Persist FAILED status without letting secondary DB errors hide the primary failure."""
    try:
        await job_repo.update_job_status(
            job_id=job_id,
            status=JobStatus.FAILED.value,
            progress_percent=progress_percent,
            current_step_message="Analysis failed.",
            error_code=error_code,
            error_message=error_message,
        )
    except Exception as exc:
        logger.exception(
            "Failed to persist FAILED status for job_id=%s",
            job_id,
            exc_info=exc,
        )


async def run_analysis_pipeline(
    *,
    job_id: str,
    user_id: str,
    cv_bytes: bytes,
    cv_content_type: str,
    linkedin_pdf_bytes: bytes | None = None,
    linkedin_content_type: str | None = None,
    cv_file_url: str | None = None,
    job_repo: JobRepository | None = None,
    profile_repo: ProfileRepository | None = None,
    matcher_callback: MatcherCallback | None = None,
    gemini_client: genai.Client | None = None,
    enrichment_http_client: httpx.AsyncClient | None = None,
    enrichment_cache: TTLCache | None = None,
    enrichment_timeout_seconds: float = ENRICHMENT_SOURCE_TIMEOUT_SECONDS,
) -> None:
    """Execute CV extraction, external enrichment, embedding, and optional matching."""
    active_job_repo = job_repo if job_repo is not None else JobRepository()
    active_profile_repo = profile_repo if profile_repo is not None else ProfileRepository()

    token = set_job_id(job_id)
    current_progress = JOB_PROGRESS_PENDING

    try:
        current_progress = JOB_PROGRESS_EXTRACTING
        await active_job_repo.update_job_status(
            job_id=job_id,
            status=JobStatus.EXTRACTING.value,
            progress_percent=JOB_PROGRESS_EXTRACTING,
            current_step_message="Extracting structured profile from CV...",
        )

        extracted_profile = await parse_cv_document(
            cv_bytes,
            cv_content_type,
            linkedin_pdf_bytes=linkedin_pdf_bytes,
            linkedin_content_type=linkedin_content_type,
            client=gemini_client,
        )

        current_progress = JOB_PROGRESS_ENRICHING
        await active_job_repo.update_job_status(
            job_id=job_id,
            status=JobStatus.ENRICHING.value,
            progress_percent=JOB_PROGRESS_ENRICHING,
            current_step_message="Enriching profile from GitHub and Portfolio...",
        )

        has_linkedin_pdf = linkedin_pdf_bytes is not None
        enriched = await enrich_profile(
            extracted_profile,
            has_linkedin_pdf=has_linkedin_pdf,
            cache=enrichment_cache,
            http_client=enrichment_http_client,
            timeout_seconds=enrichment_timeout_seconds,
        )

        current_progress = JOB_PROGRESS_EMBEDDING
        await active_job_repo.update_job_status(
            job_id=job_id,
            status=JobStatus.ENRICHING.value,
            progress_percent=JOB_PROGRESS_EMBEDDING,
            current_step_message="Generating vector embedding and saving profile...",
        )

        profile_payload: dict[str, Any] = {
            "full_name": enriched.profile.full_name,
            "headline": enriched.profile.headline,
            "years_of_experience": enriched.profile.years_of_experience,
            "cv_file_url": cv_file_url,
            "skills": [skill.model_dump() for skill in enriched.profile.skills],
            "experiences": [exp.model_dump() for exp in enriched.profile.experiences],
            "external_links": enriched.profile.external_links.model_dump(),
            "enrichment_summary": enriched.enrichment_summary.model_dump(),
            "profile_summary": enriched.profile.profile_summary,
        }
        embedding_text = build_profile_embedding_text(profile_payload)
        if embedding_text.strip():
            profile_payload["embedding"] = await generate_embedding(
                embedding_text,
                task_type=EMBEDDING_TASK_QUERY,
                client=gemini_client,
            )

        saved_profile = await active_profile_repo.create_profile(user_id, profile_payload)
        profile_id = str(saved_profile["id"])

        current_progress = JOB_PROGRESS_MATCHING
        await active_job_repo.update_job_status(
            job_id=job_id,
            status=JobStatus.MATCHING.value,
            progress_percent=JOB_PROGRESS_MATCHING,
            current_step_message="Evaluating matching companies...",
            profile_id=profile_id,
        )

        if matcher_callback is not None:
            await matcher_callback(job_id, profile_id, user_id)
        else:
            matching_service = MatchingService(
                profile_repo=active_profile_repo,
                gemini_client=gemini_client,
            )
            await matching_service.run_for_job(
                job_id=job_id,
                profile_id=profile_id,
                user_id=user_id,
                profile_data=saved_profile,
            )

        current_progress = JOB_PROGRESS_COMPLETED
        await active_job_repo.update_job_status(
            job_id=job_id,
            status=JobStatus.COMPLETED.value,
            progress_percent=JOB_PROGRESS_COMPLETED,
            current_step_message="Analysis completed.",
            profile_id=profile_id,
        )
        logger.info("Analysis pipeline completed successfully for profile_id=%s", profile_id)
    except AppException as exc:
        logger.warning(
            "Analysis pipeline failed with AppException [%s]: %s",
            exc.code,
            exc.message,
        )
        await _safe_mark_failed(
            active_job_repo,
            job_id,
            current_progress,
            exc.code,
            exc.message,
        )
    except Exception as exc:
        logger.exception("Unhandled error in analysis pipeline.", exc_info=exc)
        await _safe_mark_failed(
            active_job_repo,
            job_id,
            current_progress,
            "PIPELINE_INTERNAL_ERROR",
            "An unexpected error occurred during CV analysis.",
        )
    finally:
        reset_job_id(token)

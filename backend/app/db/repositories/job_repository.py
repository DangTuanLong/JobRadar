"""Async repository for the analysis_jobs table."""

from __future__ import annotations

from typing import Any

from supabase import Client

from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.db.supabase_client import execute_supabase_query, get_supabase_client

logger = get_logger(__name__)

TABLE_ANALYSIS_JOBS = "analysis_jobs"

JOB_STATUS_PENDING = "PENDING"
VALID_JOB_STATUSES = frozenset(
    {
        "PENDING",
        "EXTRACTING",
        "ENRICHING",
        "MATCHING",
        "COMPLETED",
        "FAILED",
    }
)
MIN_PROGRESS_PERCENT = 0
MAX_PROGRESS_PERCENT = 100


class JobRepository:
    """Data access layer for analysis_jobs records."""

    def __init__(self, client: Client | None = None) -> None:
        self._client = client

    @property
    def client(self) -> Client:
        return self._client if self._client is not None else get_supabase_client()

    async def create_job(
        self,
        user_id: str,
        profile_id: str | None = None,
    ) -> dict[str, Any]:
        """Create a new analysis job in PENDING state."""
        payload: dict[str, Any] = {
            "user_id": user_id,
            "status": JOB_STATUS_PENDING,
            "progress_percent": MIN_PROGRESS_PERCENT,
        }
        if profile_id is not None:
            payload["profile_id"] = profile_id

        response = await execute_supabase_query(
            "create_job",
            lambda: self.client.table(TABLE_ANALYSIS_JOBS).insert(payload).execute(),
        )
        rows = list(response.data or [])
        if not rows:
            logger.error("create_job returned no rows for user_id=%s", user_id)
            raise AppException(
                status_code=500,
                code="DB_INSERT_FAILED",
                message="Failed to create analysis job.",
            )
        return rows[0]

    async def get_job_by_id(self, job_id: str, user_id: str) -> dict[str, Any] | None:
        """Fetch an analysis job owned by the given user."""
        response = await execute_supabase_query(
            "get_job_by_id",
            lambda: (
                self.client.table(TABLE_ANALYSIS_JOBS)
                .select("*")
                .eq("id", job_id)
                .eq("user_id", user_id)
                .limit(1)
                .execute()
            ),
        )
        rows = list(response.data or [])
        return rows[0] if rows else None

    async def update_job_status(
        self,
        job_id: str,
        status: str,
        progress_percent: int,
        current_step_message: str | None = None,
        profile_id: str | None = None,
        error_code: str | None = None,
        error_message: str | None = None,
    ) -> dict[str, Any] | None:
        """Update job progress, status, linked profile, or failure metadata."""
        if status not in VALID_JOB_STATUSES:
            raise AppException(
                status_code=400,
                code="INVALID_JOB_STATUS",
                message=f"Unsupported job status: {status}",
            )
        if not (MIN_PROGRESS_PERCENT <= progress_percent <= MAX_PROGRESS_PERCENT):
            raise AppException(
                status_code=400,
                code="INVALID_JOB_PROGRESS",
                message="Job progress_percent must be between 0 and 100.",
            )

        payload: dict[str, Any] = {
            "status": status,
            "progress_percent": progress_percent,
        }
        if current_step_message is not None:
            payload["current_step_message"] = current_step_message
        if profile_id is not None:
            payload["profile_id"] = profile_id
        if error_code is not None:
            payload["error_code"] = error_code
        if error_message is not None:
            payload["error_message"] = error_message

        response = await execute_supabase_query(
            "update_job_status",
            lambda: (
                self.client.table(TABLE_ANALYSIS_JOBS)
                .update(payload)
                .eq("id", job_id)
                .execute()
            ),
        )
        rows = list(response.data or [])
        if not rows:
            logger.warning("update_job_status matched no record for job_id=%s", job_id)
            return None
        return rows[0]

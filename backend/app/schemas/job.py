"""Pydantic v2 response schemas for analysis job tracking."""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.core.constants import JobStatus


class AnalysisJobResponse(BaseModel):
    """Public analysis job DTO matching analysis_jobs table columns."""

    id: str
    user_id: str
    profile_id: str | None = None
    status: JobStatus | str
    progress_percent: int = Field(default=0, ge=0, le=100)
    current_step_message: str | None = None
    error_code: str | None = None
    error_message: str | None = None
    created_at: str | None = None
    updated_at: str | None = None

"""Pydantic v2 schemas for LangGraph matching evaluations and match_results DTOs."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.core.constants import (
    MATCH_WEIGHT_EXPERIENCE,
    MATCH_WEIGHT_PROJECTS,
    MATCH_WEIGHT_SKILLS,
)


class ScoreBreakdown(BaseModel):
    """Transparent 3-pillar match score components (0-100 each)."""

    skills: int = Field(ge=0, le=100)
    experience: int = Field(ge=0, le=100)
    projects: int = Field(ge=0, le=100)


class WebEvidenceItem(BaseModel):
    """Verified web snippet retrieved from Tavily search."""

    title: str
    url: str
    snippet: str = ""


class LLMMatchEvaluationItem(BaseModel):
    """Single company/JD evaluation emitted by Gemini Structured Output."""

    job_posting_id: str = Field(min_length=1)
    company_id: str = Field(min_length=1)
    match_score: int = Field(default=0, ge=0, le=100)
    score_breakdown: ScoreBreakdown
    matched_skills: list[str] = Field(default_factory=list)
    missing_skills: list[str] = Field(default_factory=list)
    reasoning: str = Field(min_length=1)


class LLMMatchBatchResponse(BaseModel):
    """Batch structured output schema used directly as Gemini response_schema."""

    evaluations: list[LLMMatchEvaluationItem] = Field(default_factory=list)


class MatchResultCreate(BaseModel):
    """Validated payload ready to persist into match_results table."""

    job_id: str
    profile_id: str
    company_id: str
    job_posting_id: str | None = None
    match_score: int = Field(ge=0, le=100)
    score_breakdown: ScoreBreakdown
    matched_skills: list[str] = Field(default_factory=list)
    missing_skills: list[str] = Field(default_factory=list)
    reasoning: str
    web_evidence: list[WebEvidenceItem] = Field(default_factory=list)


class MatchResultResponse(BaseModel):
    """Public match result DTO matching match_results columns and joined metadata."""

    id: str
    job_id: str
    profile_id: str
    company_id: str
    job_posting_id: str | None = None
    match_score: int = Field(ge=0, le=100)
    score_breakdown: ScoreBreakdown
    matched_skills: list[str] = Field(default_factory=list)
    missing_skills: list[str] = Field(default_factory=list)
    reasoning: str | None = None
    web_evidence: list[WebEvidenceItem] = Field(default_factory=list)
    companies: dict[str, Any] | None = None
    job_postings: dict[str, Any] | None = None
    created_at: str | None = None


def compute_weighted_match_score(breakdown: ScoreBreakdown) -> int:
    """Deterministically compute 0-100 match_score using 50/25/25 pillar weights."""
    weighted = (
        MATCH_WEIGHT_SKILLS * breakdown.skills
        + MATCH_WEIGHT_EXPERIENCE * breakdown.experience
        + MATCH_WEIGHT_PROJECTS * breakdown.projects
    )
    return max(0, min(100, round(weighted)))

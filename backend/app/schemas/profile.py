"""Pydantic v2 schemas for candidate profile extraction, updates, and API responses."""

from __future__ import annotations

import re
from typing import Any, Literal
from urllib.parse import urlsplit, urlunsplit

from pydantic import BaseModel, Field, field_validator, model_validator

SkillCategory = Literal[
    "language",
    "framework",
    "database",
    "cloud_devops",
    "ai_ml",
    "tool",
    "soft_skill",
    "other",
]
SkillLevel = Literal["beginner", "intermediate", "advanced", "expert"]
ExperienceType = Literal["work", "project", "education", "other"]

_IGNORED_URL_PREFIXES = ("mailto:", "tel:", "javascript:", "data:", "sms:")
_TRAILING_URL_JUNK = ".,;)>]}\"'`"
_DOMAIN_PATTERN = re.compile(r"^[a-z0-9.-]+\.[a-z]{2,}(:\d+)?$")


def normalize_url(raw_url: str | None) -> str | None:
    """Normalize and validate external HTTP(S) URLs, returning None if invalid."""
    if raw_url is None or not isinstance(raw_url, str):
        return None

    cleaned = raw_url.strip().rstrip(_TRAILING_URL_JUNK).strip()
    if not cleaned:
        return None

    lowered = cleaned.lower()
    if lowered.startswith(_IGNORED_URL_PREFIXES):
        return None

    if "://" not in cleaned:
        # Reject email-like strings without mailto: prefix.
        first_segment = cleaned.split("/", maxsplit=1)[0]
        if "@" in first_segment or " " in cleaned or "." not in first_segment:
            return None
        cleaned = f"https://{cleaned}"

    try:
        parsed = urlsplit(cleaned)
    except ValueError:
        return None

    scheme = parsed.scheme.lower()
    netloc = parsed.netloc.lower()
    if scheme not in {"http", "https"} or not netloc or "@" in netloc or " " in netloc:
        return None
    if not _DOMAIN_PATTERN.match(netloc):
        return None

    normalized_path = parsed.path.rstrip("/")
    return urlunsplit((scheme, netloc, normalized_path, parsed.query, ""))


class SkillItem(BaseModel):
    """Normalized skill entry stored inside candidate_profiles.skills JSONB."""

    name: str = Field(min_length=1)
    category: SkillCategory = "other"
    level: SkillLevel = "intermediate"
    verified_by: list[str] = Field(default_factory=list)

    @field_validator("name")
    @classmethod
    def strip_name(cls, value: str) -> str:
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("Skill name must not be blank.")
        return cleaned


class ExperienceItem(BaseModel):
    """Normalized work, project, or education entry inside experiences JSONB."""

    title: str = Field(min_length=1)
    organization: str | None = None
    item_type: ExperienceType = "work"
    duration: str | None = None
    description: str | None = None
    technologies: list[str] = Field(default_factory=list)


class ExternalLinks(BaseModel):
    """Validated candidate profile links stored in external_links JSONB."""

    github: str | None = None
    portfolio: str | None = None
    linkedin: str | None = None
    other: list[str] = Field(default_factory=list)

    @field_validator("github", "portfolio", "linkedin", mode="before")
    @classmethod
    def validate_single_url(cls, value: Any) -> str | None:
        return normalize_url(value) if isinstance(value, str) else None

    @field_validator("other", mode="before")
    @classmethod
    def validate_other_urls(cls, value: Any) -> list[str]:
        if not isinstance(value, list):
            return []
        normalized: list[str] = []
        seen: set[str] = set()
        for item in value:
            url = normalize_url(item) if isinstance(item, str) else None
            if url and url not in seen:
                seen.add(url)
                normalized.append(url)
        return normalized

    @model_validator(mode="after")
    def deduplicate_other(self) -> ExternalLinks:
        primary = {
            url
            for url in (self.github, self.portfolio, self.linkedin)
            if url is not None
        }
        self.other = [url for url in self.other if url not in primary]
        return self


class EnrichmentSummary(BaseModel):
    """Summary metadata gathered from GitHub, Portfolio, and LinkedIn."""

    github_username: str | None = None
    github_public_repos: int = 0
    github_total_stars: int = 0
    github_top_languages: list[str] = Field(default_factory=list)
    github_highlights: list[str] = Field(default_factory=list)
    portfolio_summary: str | None = None
    linkedin_verified: bool = False
    sources_fetched: list[str] = Field(default_factory=list)
    fetch_warnings: list[str] = Field(default_factory=list)


class CandidateProfileExtracted(BaseModel):
    """Structured output schema used directly by Gemini for CV extraction."""

    full_name: str | None = None
    headline: str | None = None
    years_of_experience: float = Field(default=0.0, ge=0.0, le=60.0)
    skills: list[SkillItem] = Field(default_factory=list)
    experiences: list[ExperienceItem] = Field(default_factory=list)
    external_links: ExternalLinks = Field(default_factory=ExternalLinks)
    profile_summary: str | None = None


class CandidateProfileUpdate(BaseModel):
    """Request payload for manual candidate profile edits."""

    full_name: str | None = None
    headline: str | None = None
    years_of_experience: float | None = Field(default=None, ge=0.0, le=60.0)
    skills: list[SkillItem] | None = None
    experiences: list[ExperienceItem] | None = None
    external_links: ExternalLinks | None = None
    profile_summary: str | None = None


class CandidateProfileResponse(BaseModel):
    """Public candidate profile DTO matching candidate_profiles table columns."""

    id: str
    user_id: str
    full_name: str | None = None
    headline: str | None = None
    years_of_experience: float = 0.0
    cv_file_url: str | None = None
    skills: list[SkillItem] = Field(default_factory=list)
    experiences: list[ExperienceItem] = Field(default_factory=list)
    external_links: ExternalLinks = Field(default_factory=ExternalLinks)
    enrichment_summary: EnrichmentSummary = Field(default_factory=EnrichmentSummary)
    profile_summary: str | None = None
    created_at: str | None = None
    updated_at: str | None = None

"""Centralized domain and infrastructure constants for JobRadar Backend."""

from __future__ import annotations

from enum import StrEnum

# File upload & validation constraints
MAX_CV_FILE_SIZE_BYTES = 5 * 1024 * 1024  # 5 MB
MAX_PDF_PAGES = 10

MIME_PDF = "application/pdf"
MIME_PNG = "image/png"
MIME_JPEG = "image/jpeg"

ALLOWED_CV_MIME_TYPES = frozenset({MIME_PDF, MIME_PNG, MIME_JPEG})

PDF_MAGIC_BYTES = b"%PDF-"
PNG_MAGIC_BYTES = b"\x89PNG"
JPEG_MAGIC_BYTES = b"\xff\xd8\xff"

# Vector embedding configuration (must match Phase 1 pgvector schema)
EMBEDDING_DIM = 768
EMBEDDING_TASK_DOCUMENT = "RETRIEVAL_DOCUMENT"
EMBEDDING_TASK_QUERY = "RETRIEVAL_QUERY"

# Gemini API retry configuration
GEMINI_MAX_RETRIES = 4
GEMINI_RETRY_BASE_DELAY_SECONDS = 2.0
GEMINI_RATE_LIMIT_BASE_DELAY_SECONDS = 8.0

# Skill ranking for deduplication
SKILL_LEVEL_RANK: dict[str, int] = {
    "beginner": 1,
    "intermediate": 2,
    "advanced": 3,
    "expert": 4,
}


class JobStatus(StrEnum):
    """Lifecycle states of an asynchronous analysis job."""

    PENDING = "PENDING"
    EXTRACTING = "EXTRACTING"
    ENRICHING = "ENRICHING"
    MATCHING = "MATCHING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class VerificationSource(StrEnum):
    """Evidence sources that verify a candidate's skills or profile."""

    CV = "cv"
    GITHUB = "github"
    PORTFOLIO = "portfolio"
    LINKEDIN_PDF = "linkedin_pdf"

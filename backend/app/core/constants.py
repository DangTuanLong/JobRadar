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

# Enrichment & timeout configuration
ENRICHMENT_SOURCE_TIMEOUT_SECONDS = 10.0
ENRICHMENT_CACHE_TTL_SECONDS = 86400
ENRICHMENT_CACHE_MAXSIZE = 500
GITHUB_MAX_REPOS_FETCH = 10
GITHUB_TOP_REPOS_LIMIT = 5
GITHUB_README_MAX_CHARS = 1500
PORTFOLIO_MAX_CHARS = 4000
PORTFOLIO_SUMMARY_MAX_CHARS = 500

# Supabase Storage
SUPABASE_CV_BUCKET = "cvs"

# LangGraph Hybrid Matching Agent configuration
MATCH_VECTOR_THRESHOLD_PRIMARY = 0.40
MATCH_VECTOR_THRESHOLD_FALLBACK = 0.25
MATCH_MIN_CANDIDATES_BEFORE_FALLBACK = 3
MATCH_RETRIEVE_LIMIT = 15
MATCH_MAX_CANDIDATES_FOR_LLM = 10
MATCH_TAVILY_TOP_COMPANIES = 4
MATCH_TOP_RESULTS_TO_SAVE = 8
TAVILY_TIMEOUT_SECONDS = 8.0
TAVILY_MAX_RESULTS_PER_COMPANY = 3
TAVILY_SNIPPET_MAX_CHARS = 300
MATCH_WEIGHT_SKILLS = 0.50
MATCH_WEIGHT_EXPERIENCE = 0.25
MATCH_WEIGHT_PROJECTS = 0.25

# Pipeline progress milestones
JOB_PROGRESS_PENDING = 0
JOB_PROGRESS_EXTRACTING = 20
JOB_PROGRESS_ENRICHING = 50
JOB_PROGRESS_EMBEDDING = 75
JOB_PROGRESS_MATCHING = 85
JOB_PROGRESS_COMPLETED = 100

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

"""Multimodal CV validation, PDF hyperlink extraction, and Gemini parsing service."""

from __future__ import annotations

import asyncio
from typing import Any
from urllib.parse import urlsplit

import pymupdf
from google import genai
from google.genai import types
from pydantic import ValidationError

from app.core.config import settings
from app.core.constants import (
    ALLOWED_CV_MIME_TYPES,
    JPEG_MAGIC_BYTES,
    MAX_CV_FILE_SIZE_BYTES,
    MAX_PDF_PAGES,
    MIME_JPEG,
    MIME_PDF,
    MIME_PNG,
    PDF_MAGIC_BYTES,
    PNG_MAGIC_BYTES,
    SKILL_LEVEL_RANK,
    VerificationSource,
)
from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.prompts.cv_extraction import (
    CV_EXTRACTION_SYSTEM_INSTRUCTION,
    build_cv_extraction_user_prompt,
)
from app.schemas.profile import (
    CandidateProfileExtracted,
    ExternalLinks,
    SkillItem,
    normalize_url,
)
from app.services.gemini_client import (
    build_thinking_config,
    call_gemini_with_fallback,
    get_gemini_client,
)

logger = get_logger(__name__)

_MAGIC_BYTES_BY_MIME: dict[str, bytes] = {
    MIME_PDF: PDF_MAGIC_BYTES,
    MIME_PNG: PNG_MAGIC_BYTES,
    MIME_JPEG: JPEG_MAGIC_BYTES,
}


def validate_cv_file(
    file_bytes: bytes,
    content_type: str | None,
    *,
    allowed_mime_types: frozenset[str] = ALLOWED_CV_MIME_TYPES,
) -> str:
    """Validate non-empty size, file size <= 5MB, MIME type, and actual magic bytes."""
    if not file_bytes:
        raise AppException(status_code=400, code="CV_FILE_EMPTY", message="Uploaded file is empty.")

    if len(file_bytes) > MAX_CV_FILE_SIZE_BYTES:
        raise AppException(
            status_code=400,
            code="CV_FILE_TOO_LARGE",
            message="Uploaded file exceeds the 5MB limit.",
        )

    normalized_mime = (content_type or "").split(";", maxsplit=1)[0].strip().lower()
    if normalized_mime not in allowed_mime_types:
        raise AppException(
            status_code=400,
            code="CV_UNSUPPORTED_FORMAT",
            message="Unsupported file format. Only PDF, PNG, and JPEG are allowed.",
        )

    if not file_bytes.startswith(_MAGIC_BYTES_BY_MIME[normalized_mime]):
        logger.warning("File magic bytes mismatch for declared MIME type '%s'.", normalized_mime)
        raise AppException(
            status_code=400,
            code="CV_UNSUPPORTED_FORMAT",
            message="File content does not match its declared format.",
        )

    return normalized_mime


def extract_pdf_links_sync(pdf_bytes: bytes) -> list[str]:
    """Validate a PDF document and extract deduplicated HTTP(S) annotation hyperlinks."""
    try:
        doc = pymupdf.open(stream=pdf_bytes, filetype="pdf")
    except Exception as exc:
        logger.warning("Failed to open PDF stream with PyMuPDF: %s", exc)
        raise AppException(
            status_code=400,
            code="CV_CORRUPT_FILE",
            message="Could not read PDF file. The file may be corrupted.",
        ) from exc

    try:
        if doc.needs_pass or doc.is_encrypted:
            raise AppException(
                status_code=400,
                code="CV_CORRUPT_FILE",
                message="Password-protected or encrypted PDF files are not supported.",
            )
        if doc.page_count <= 0:
            raise AppException(
                status_code=400,
                code="CV_CORRUPT_FILE",
                message="PDF file contains no readable pages.",
            )
        if doc.page_count > MAX_PDF_PAGES:
            raise AppException(
                status_code=400,
                code="CV_TOO_MANY_PAGES",
                message=f"PDF exceeds the maximum limit of {MAX_PDF_PAGES} pages.",
            )

        links: list[str] = []
        seen: set[str] = set()
        for page in doc:
            for link in page.get_links():
                raw_uri = link.get("uri")
                normalized = normalize_url(raw_uri) if isinstance(raw_uri, str) else None
                if normalized and normalized not in seen:
                    seen.add(normalized)
                    links.append(normalized)
        return links
    finally:
        doc.close()


def _deduplicate_skills(skills: list[SkillItem], *, verified_sources: list[str]) -> list[SkillItem]:
    """Merge duplicate skills case-insensitively, keeping the highest proficiency level."""
    merged: dict[str, SkillItem] = {}
    for skill in skills:
        key = skill.name.strip().lower()
        if not key:
            continue
        candidate = SkillItem(
            name=skill.name.strip(),
            category=skill.category,
            level=skill.level,
            verified_by=list(verified_sources),
        )
        existing = merged.get(key)
        if existing is None:
            merged[key] = candidate
            continue
        if SKILL_LEVEL_RANK[candidate.level] > SKILL_LEVEL_RANK[existing.level]:
            existing.level = candidate.level
        if existing.category == "other" and candidate.category != "other":
            existing.category = candidate.category
    return list(merged.values())


def _merge_external_links(links: ExternalLinks, extracted_urls: list[str]) -> ExternalLinks:
    """Merge PDF annotation links into ExternalLinks when LLM output missed them."""
    github, linkedin, portfolio = links.github, links.linkedin, links.portfolio
    other = list(links.other)
    for url in extracted_urls:
        host = urlsplit(url).netloc.lower()
        if host == "github.com" or host.endswith(".github.com"):
            if github is None:
                github = url
            elif url != github and url not in other:
                other.append(url)
        elif host == "linkedin.com" or host.endswith(".linkedin.com"):
            if linkedin is None:
                linkedin = url
            elif url != linkedin and url not in other:
                other.append(url)
        else:
            if portfolio is None:
                portfolio = url
            elif url != portfolio and url not in other:
                other.append(url)
    return ExternalLinks(github=github, portfolio=portfolio, linkedin=linkedin, other=other)


def _postprocess_extracted_profile(
    profile: CandidateProfileExtracted,
    *,
    extracted_urls: list[str],
    has_linkedin_pdf: bool,
) -> CandidateProfileExtracted:
    """Validate meaningfulness, deduplicate skills, tag provenance, and merge hyperlinks."""
    has_summary = bool(profile.profile_summary and profile.profile_summary.strip())
    if not profile.skills and not profile.experiences and not has_summary:
        raise AppException(
            status_code=422,
            code="CV_PARSE_FAILED",
            message="Could not extract meaningful CV information from the uploaded file.",
        )

    verified_sources = [VerificationSource.CV.value]
    if has_linkedin_pdf:
        verified_sources.append(VerificationSource.LINKEDIN_PDF.value)
    return CandidateProfileExtracted(
        full_name=profile.full_name.strip() if profile.full_name else None,
        headline=profile.headline.strip() if profile.headline else None,
        years_of_experience=profile.years_of_experience,
        skills=_deduplicate_skills(profile.skills, verified_sources=verified_sources),
        experiences=profile.experiences,
        external_links=_merge_external_links(profile.external_links, extracted_urls),
        profile_summary=profile.profile_summary.strip() if profile.profile_summary else None,
    )


def _parse_gemini_profile_response(response: object) -> CandidateProfileExtracted:
    """Parse Gemini response.parsed or JSON text into CandidateProfileExtracted."""
    parsed = getattr(response, "parsed", None)
    if isinstance(parsed, CandidateProfileExtracted):
        return parsed
    if isinstance(parsed, dict):
        return CandidateProfileExtracted.model_validate(parsed)

    raw_text = getattr(response, "text", None)
    if isinstance(raw_text, str) and raw_text.strip():
        return CandidateProfileExtracted.model_validate_json(raw_text)
    raise ValueError("Gemini response contained neither parsed object nor JSON text.")


async def parse_cv_document(
    cv_bytes: bytes,
    cv_content_type: str | None,
    *,
    linkedin_pdf_bytes: bytes | None = None,
    linkedin_content_type: str | None = None,
    client: genai.Client | None = None,
) -> CandidateProfileExtracted:
    """Validate CV file(s), extract hidden PDF hyperlinks, and parse via Gemini Multimodal."""
    cv_mime = validate_cv_file(cv_bytes, cv_content_type)
    extracted_links: list[str] = []
    if cv_mime == MIME_PDF:
        extracted_links.extend(await asyncio.to_thread(extract_pdf_links_sync, cv_bytes))

    has_linkedin_pdf = False
    if linkedin_pdf_bytes is not None:
        validate_cv_file(
            linkedin_pdf_bytes,
            linkedin_content_type or MIME_PDF,
            allowed_mime_types=frozenset({MIME_PDF}),
        )
        for url in await asyncio.to_thread(extract_pdf_links_sync, linkedin_pdf_bytes):
            if url not in extracted_links:
                extracted_links.append(url)
        has_linkedin_pdf = True

    parts: list[types.Part | str] = [types.Part.from_bytes(data=cv_bytes, mime_type=cv_mime)]
    if has_linkedin_pdf and linkedin_pdf_bytes is not None:
        parts.append(types.Part.from_bytes(data=linkedin_pdf_bytes, mime_type=MIME_PDF))
    user_prompt = build_cv_extraction_user_prompt(
        extracted_links, has_linkedin_pdf=has_linkedin_pdf
    )
    parts.append(user_prompt)

    gemini_client = client if client is not None else get_gemini_client()

    async def _call_model(model_name: str) -> Any:
        thinking = build_thinking_config(model_name, settings.GEMINI_EXTRACTION_THINKING_LEVEL)
        config = types.GenerateContentConfig(
            system_instruction=CV_EXTRACTION_SYSTEM_INSTRUCTION,
            response_mime_type="application/json",
            response_schema=CandidateProfileExtracted,
            thinking_config=thinking,
        )
        return await gemini_client.aio.models.generate_content(
            model=model_name, contents=parts, config=config
        )

    response = await call_gemini_with_fallback(
        "cv_extraction",
        _call_model,
        primary_model=settings.GEMINI_MODEL_EXTRACTION,
        fallback_model=settings.GEMINI_MODEL_FALLBACK,
        timeout_seconds=settings.GEMINI_EXTRACTION_TIMEOUT_SECONDS,
    )

    try:
        extracted_profile = _parse_gemini_profile_response(response)
    except (ValidationError, ValueError) as exc:
        logger.warning("Gemini CV structured output failed validation: %s", exc)
        raise AppException(
            status_code=422,
            code="CV_PARSE_FAILED",
            message="Could not parse structured candidate profile from Gemini response.",
        ) from exc

    return _postprocess_extracted_profile(
        extracted_profile,
        extracted_urls=extracted_links,
        has_linkedin_pdf=has_linkedin_pdf,
    )

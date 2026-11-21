"""Offline unit tests for CV validation, PyMuPDF link extraction, and Gemini parsing."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock

import pymupdf
import pytest
from google.genai import types

from app.core.constants import MAX_CV_FILE_SIZE_BYTES, MAX_PDF_PAGES, MIME_JPEG, MIME_PDF, MIME_PNG
from app.core.exceptions import AppException
from app.schemas.profile import CandidateProfileExtracted, ExternalLinks, SkillItem
from app.services.cv_parser_service import (
    extract_pdf_links_sync,
    parse_cv_document,
    validate_cv_file,
)


def _create_pdf_bytes(
    *,
    num_pages: int = 1,
    hyperlink_uri: str | None = None,
    text: str = "Nguyen Van A - Android Developer",
) -> bytes:
    doc = pymupdf.open()
    for idx in range(num_pages):
        page = doc.new_page()
        page.insert_text((72, 72), f"{text} (page {idx + 1})")
        if idx == 0 and hyperlink_uri:
            link_rect = pymupdf.Rect(72, 90, 260, 110)
            page.insert_text((72, 105), "GitHub Profile")
            page.insert_link(
                {
                    "kind": pymupdf.LINK_URI,
                    "from": link_rect,
                    "uri": hyperlink_uri,
                }
            )
    pdf_bytes = doc.tobytes()
    doc.close()
    return pdf_bytes


def _mock_gemini_client(parsed_profile: CandidateProfileExtracted) -> Any:
    generate_mock = AsyncMock(
        return_value=SimpleNamespace(
            parsed=parsed_profile,
            text=parsed_profile.model_dump_json(),
        )
    )
    return SimpleNamespace(
        aio=SimpleNamespace(
            models=SimpleNamespace(generate_content=generate_mock),
        ),
        _generate_mock=generate_mock,
    )


def test_validate_cv_file_rejects_empty_file() -> None:
    with pytest.raises(AppException) as exc_info:
        validate_cv_file(b"", MIME_PDF)

    assert exc_info.value.status_code == 400
    assert exc_info.value.code == "CV_FILE_EMPTY"


def test_validate_cv_file_rejects_file_over_5mb() -> None:
    oversized = b"%PDF-" + b"a" * MAX_CV_FILE_SIZE_BYTES
    with pytest.raises(AppException) as exc_info:
        validate_cv_file(oversized, MIME_PDF)

    assert exc_info.value.status_code == 400
    assert exc_info.value.code == "CV_FILE_TOO_LARGE"


def test_validate_cv_file_rejects_spoofed_mime_magic_bytes() -> None:
    fake_pdf = b"This is plain text pretending to be a PDF"
    with pytest.raises(AppException) as exc_info:
        validate_cv_file(fake_pdf, MIME_PDF)

    assert exc_info.value.status_code == 400
    assert exc_info.value.code == "CV_UNSUPPORTED_FORMAT"


def test_validate_cv_file_accepts_valid_png_and_jpeg_headers() -> None:
    assert validate_cv_file(b"\x89PNG\r\n\x1a\nfake-png-data", MIME_PNG) == MIME_PNG
    assert validate_cv_file(b"\xff\xd8\xff\xe0fake-jpeg-data", MIME_JPEG) == MIME_JPEG


def test_extract_pdf_links_rejects_corrupt_pdf() -> None:
    with pytest.raises(AppException) as exc_info:
        extract_pdf_links_sync(b"%PDF-corrupt-garbage-bytes")

    assert exc_info.value.status_code == 400
    assert exc_info.value.code == "CV_CORRUPT_FILE"


def test_extract_pdf_links_rejects_over_10_pages() -> None:
    too_many_pages = _create_pdf_bytes(num_pages=MAX_PDF_PAGES + 1)
    with pytest.raises(AppException) as exc_info:
        extract_pdf_links_sync(too_many_pages)

    assert exc_info.value.status_code == 400
    assert exc_info.value.code == "CV_TOO_MANY_PAGES"


@pytest.mark.asyncio
async def test_parse_cv_extracts_hidden_github_link_and_deduplicates_skills() -> None:
    pdf_bytes = _create_pdf_bytes(hyperlink_uri="https://github.com/example-dev/")
    raw_llm_profile = CandidateProfileExtracted(
        full_name="Nguyen Van A",
        headline="Android Developer",
        years_of_experience=1.5,
        skills=[
            SkillItem(name="kotlin", category="language", level="beginner"),
            SkillItem(name="Kotlin", category="other", level="advanced"),
            SkillItem(name="FastAPI", category="framework", level="intermediate"),
        ],
        external_links=ExternalLinks(
            github=None,
            portfolio="portfolio.example.dev/",
            linkedin="mailto:candidate@example.com",
            other=["tel:+84901234567", "not-a-valid-url"],
        ),
        profile_summary="Android developer experienced in Kotlin and FastAPI.",
    )
    mock_client = _mock_gemini_client(raw_llm_profile)

    result = await parse_cv_document(pdf_bytes, MIME_PDF, client=mock_client)

    assert result.external_links.github == "https://github.com/example-dev"
    assert result.external_links.portfolio == "https://portfolio.example.dev"
    assert result.external_links.linkedin is None
    assert result.external_links.other == []

    assert len(result.skills) == 2
    kotlin_skill = next(s for s in result.skills if s.name.lower() == "kotlin")
    assert kotlin_skill.level == "advanced"
    assert kotlin_skill.category == "language"
    assert kotlin_skill.verified_by == ["cv"]

    mock_client._generate_mock.assert_awaited_once()
    call_kwargs = mock_client._generate_mock.call_args.kwargs
    assert call_kwargs["model"] == "gemini-4-hydrogen"
    assert call_kwargs["config"].thinking_config is not None
    assert call_kwargs["config"].thinking_config.thinking_level == types.ThinkingLevel.LOW
    prompt_text = call_kwargs["contents"][-1]
    assert "https://github.com/example-dev" in prompt_text


@pytest.mark.asyncio
async def test_parse_cv_with_linkedin_pdf_tags_both_sources() -> None:
    cv_bytes = _create_pdf_bytes(hyperlink_uri="https://github.com/example-dev")
    linkedin_bytes = _create_pdf_bytes(
        hyperlink_uri="https://www.linkedin.com/in/example-dev",
        text="LinkedIn Profile Summary",
    )
    llm_profile = CandidateProfileExtracted(
        full_name="Nguyen Van A",
        headline="Backend Python Developer",
        years_of_experience=2.0,
        skills=[SkillItem(name="Python", category="language", level="advanced")],
        profile_summary="Backend engineer specializing in Python and FastAPI.",
    )
    mock_client = _mock_gemini_client(llm_profile)

    result = await parse_cv_document(
        cv_bytes,
        MIME_PDF,
        linkedin_pdf_bytes=linkedin_bytes,
        linkedin_content_type=MIME_PDF,
        client=mock_client,
    )

    assert result.external_links.github == "https://github.com/example-dev"
    assert result.external_links.linkedin == "https://www.linkedin.com/in/example-dev"
    assert result.skills[0].verified_by == ["cv", "linkedin_pdf"]
    call_kwargs = mock_client._generate_mock.call_args.kwargs
    # Expect primary CV Part, LinkedIn PDF Part, and prompt string.
    assert len(call_kwargs["contents"]) == 3


@pytest.mark.asyncio
async def test_parse_cv_raises_422_when_document_has_no_meaningful_content() -> None:
    cv_bytes = _create_pdf_bytes(text="Random blank receipt")
    empty_profile = CandidateProfileExtracted(
        skills=[],
        experiences=[],
        profile_summary=None,
    )
    mock_client = _mock_gemini_client(empty_profile)

    with pytest.raises(AppException) as exc_info:
        await parse_cv_document(cv_bytes, MIME_PDF, client=mock_client)

    assert exc_info.value.status_code == 422
    assert exc_info.value.code == "CV_PARSE_FAILED"

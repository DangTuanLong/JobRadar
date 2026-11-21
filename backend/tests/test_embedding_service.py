"""Offline unit tests for Gemini embedding service and retry logic."""

from __future__ import annotations

import math
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from google.genai import errors as genai_errors

from app.core.constants import EMBEDDING_DIM, EMBEDDING_TASK_QUERY
from app.core.exceptions import AppException
from app.schemas.profile import (
    CandidateProfileExtracted,
    ExperienceItem,
    SkillItem,
)
from app.services.embedding_service import (
    build_profile_embedding_text,
    generate_embedding,
    l2_normalize,
)


def test_l2_normalize_produces_unit_norm() -> None:
    raw = [3.0, 4.0] + [0.0] * (EMBEDDING_DIM - 2)
    normalized = l2_normalize(raw)

    assert len(normalized) == EMBEDDING_DIM
    norm = math.sqrt(sum(v * v for v in normalized))
    assert norm == pytest.approx(1.0, rel=1e-6)
    assert normalized[0] == pytest.approx(0.6, rel=1e-6)
    assert normalized[1] == pytest.approx(0.8, rel=1e-6)


def test_build_profile_embedding_text_includes_all_key_sections() -> None:
    profile = CandidateProfileExtracted(
        full_name="Tran Thi B",
        headline="Junior Backend Engineer",
        years_of_experience=1.5,
        skills=[SkillItem(name="Python", category="language", level="advanced")],
        experiences=[
            ExperienceItem(
                title="JobRadar API",
                organization="Personal Project",
                item_type="project",
                technologies=["FastAPI", "Supabase"],
                description="Built async CV analysis backend.",
            )
        ],
        profile_summary="Backend developer focused on Python and AI agents.",
    )

    text = build_profile_embedding_text(profile)

    assert "Headline: Junior Backend Engineer" in text
    assert "Years of Experience: 1.5" in text
    assert "Skills: Python (advanced)" in text
    assert "JobRadar API at Personal Project" in text
    assert "FastAPI, Supabase" in text
    assert "Summary: Backend developer focused on Python and AI agents." in text


@pytest.mark.asyncio
async def test_generate_embedding_returns_768_dim_l2_normalized_vector() -> None:
    raw_values = [float((idx % 5) + 1) for idx in range(EMBEDDING_DIM)]
    embed_mock = AsyncMock(
        return_value=SimpleNamespace(embeddings=[SimpleNamespace(values=raw_values)])
    )
    mock_client = SimpleNamespace(
        aio=SimpleNamespace(models=SimpleNamespace(embed_content=embed_mock))
    )

    vector = await generate_embedding(
        "Kotlin Android Developer",
        task_type=EMBEDDING_TASK_QUERY,
        client=mock_client,
    )

    assert len(vector) == EMBEDDING_DIM
    assert math.sqrt(sum(v * v for v in vector)) == pytest.approx(1.0, rel=1e-6)
    call_kwargs = embed_mock.call_args.kwargs
    assert call_kwargs["config"].task_type == EMBEDDING_TASK_QUERY
    assert call_kwargs["config"].output_dimensionality == EMBEDDING_DIM


@pytest.mark.asyncio
async def test_generate_embedding_retries_on_429_rate_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sleep_mock = AsyncMock()
    monkeypatch.setattr("app.services.gemini_client.asyncio.sleep", sleep_mock)

    rate_limit_error = genai_errors.APIError(
        429,
        {"error": {"code": 429, "message": "Quota exceeded", "status": "RESOURCE_EXHAUSTED"}},
    )
    raw_values = [1.0] * EMBEDDING_DIM
    embed_mock = AsyncMock(
        side_effect=[
            rate_limit_error,
            rate_limit_error,
            SimpleNamespace(embeddings=[SimpleNamespace(values=raw_values)]),
        ]
    )
    mock_client = SimpleNamespace(
        aio=SimpleNamespace(models=SimpleNamespace(embed_content=embed_mock))
    )

    vector = await generate_embedding("Retry test", client=mock_client)

    assert len(vector) == EMBEDDING_DIM
    assert math.sqrt(sum(v * v for v in vector)) == pytest.approx(1.0, rel=1e-6)
    assert embed_mock.await_count == 3
    assert sleep_mock.await_count == 2


@pytest.mark.asyncio
async def test_generate_embedding_raises_429_when_retries_exhausted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sleep_mock = AsyncMock()
    monkeypatch.setattr("app.services.gemini_client.asyncio.sleep", sleep_mock)

    rate_limit_error = genai_errors.APIError(
        429,
        {"error": {"code": 429, "message": "Quota exceeded", "status": "RESOURCE_EXHAUSTED"}},
    )
    embed_mock = AsyncMock(side_effect=rate_limit_error)
    mock_client = SimpleNamespace(
        aio=SimpleNamespace(models=SimpleNamespace(embed_content=embed_mock))
    )

    with pytest.raises(AppException) as exc_info:
        await generate_embedding("Exhausted retry test", client=mock_client)

    assert exc_info.value.status_code == 429
    assert exc_info.value.code == "AI_RATE_LIMITED"

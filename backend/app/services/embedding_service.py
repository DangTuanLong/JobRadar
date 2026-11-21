"""Shared semantic embedding generation and profile text builder."""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from typing import Any

from google import genai
from google.genai import types
from pydantic import BaseModel

from app.core.config import settings
from app.core.constants import EMBEDDING_DIM, EMBEDDING_TASK_DOCUMENT
from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.services.gemini_client import call_gemini_with_retry, get_gemini_client

logger = get_logger(__name__)


def l2_normalize(vector: Sequence[float]) -> list[float]:
    """Normalize a numeric vector to unit Euclidean norm."""
    if not vector:
        raise AppException(
            status_code=502,
            code="EMBEDDING_INVALID_VECTOR",
            message="Embedding model returned an empty vector.",
        )
    norm = math.sqrt(sum(v * v for v in vector))
    if norm == 0.0:
        raise AppException(
            status_code=502,
            code="EMBEDDING_INVALID_VECTOR",
            message="Embedding model returned a zero-magnitude vector.",
        )
    return [float(v) / norm for v in vector]


def _get_field(source: BaseModel | Mapping[str, Any], key: str, default: Any = None) -> Any:
    if isinstance(source, Mapping):
        return source.get(key, default)
    return getattr(source, key, default)


def _format_skills(skills: Any) -> str:
    if not isinstance(skills, list) or not skills:
        return ""
    formatted: list[str] = []
    for item in skills:
        name = _get_field(item, "name") if isinstance(item, (BaseModel, Mapping)) else str(item)
        level = _get_field(item, "level") if isinstance(item, (BaseModel, Mapping)) else None
        if not name:
            continue
        formatted.append(f"{name} ({level})" if level else str(name))
    return ", ".join(formatted)


def _format_experiences(experiences: Any) -> str:
    if not isinstance(experiences, list) or not experiences:
        return ""
    entries: list[str] = []
    for exp in experiences:
        if not isinstance(exp, (BaseModel, Mapping)):
            continue
        title = _get_field(exp, "title")
        org = _get_field(exp, "organization")
        techs = _get_field(exp, "technologies") or []
        desc = _get_field(exp, "description")

        parts: list[str] = []
        if title:
            parts.append(f"{title} at {org}" if org else str(title))
        if isinstance(techs, list) and techs:
            parts.append(f"Tech: {', '.join(str(t) for t in techs if t)}")
        if desc:
            parts.append(str(desc).strip())
        if parts:
            entries.append(" | ".join(parts))
    return "; ".join(entries)


def build_profile_embedding_text(profile: BaseModel | Mapping[str, Any]) -> str:
    """Build a dense semantic text representation from a candidate profile."""
    sections: list[str] = []

    headline = _get_field(profile, "headline")
    if headline:
        sections.append(f"Headline: {str(headline).strip()}")

    years = _get_field(profile, "years_of_experience")
    if years is not None:
        sections.append(f"Years of Experience: {float(years):.1f}")

    skills_text = _format_skills(_get_field(profile, "skills"))
    if skills_text:
        sections.append(f"Skills: {skills_text}")

    experiences_text = _format_experiences(_get_field(profile, "experiences"))
    if experiences_text:
        sections.append(f"Projects and Experience: {experiences_text}")

    enrichment = _get_field(profile, "enrichment_summary")
    if isinstance(enrichment, (BaseModel, Mapping)):
        top_langs = _get_field(enrichment, "github_top_languages") or []
        highlights = _get_field(enrichment, "github_highlights") or []
        if top_langs:
            sections.append(f"GitHub Languages: {', '.join(str(lang) for lang in top_langs)}")
        if highlights:
            sections.append(f"GitHub Highlights: {'; '.join(str(h) for h in highlights)}")

    summary = _get_field(profile, "profile_summary")
    if summary:
        sections.append(f"Summary: {str(summary).strip()}")

    return "\n".join(sections)


async def generate_embeddings(
    texts: list[str],
    *,
    task_type: str = EMBEDDING_TASK_DOCUMENT,
    client: genai.Client | None = None,
) -> list[list[float]]:
    """Generate L2-normalized 768-dim Gemini embeddings for a list of texts."""
    if not texts or any(not text or not text.strip() for text in texts):
        raise AppException(
            status_code=400,
            code="EMBEDDING_EMPTY_INPUT",
            message="Cannot generate embeddings for empty text.",
        )

    gemini_client = client if client is not None else get_gemini_client()
    response = await call_gemini_with_retry(
        "embed_content",
        lambda: gemini_client.aio.models.embed_content(
            model=settings.GEMINI_MODEL_EMBEDDING,
            contents=texts,
            config=types.EmbedContentConfig(
                task_type=task_type,
                output_dimensionality=EMBEDDING_DIM,
            ),
        ),
    )

    embeddings = getattr(response, "embeddings", None) or []
    if len(embeddings) != len(texts):
        logger.error(
            "Embedding count mismatch: expected %d, got %d",
            len(texts),
            len(embeddings),
        )
        raise AppException(
            status_code=502,
            code="EMBEDDING_INVALID_VECTOR",
            message="Embedding model returned an unexpected number of vectors.",
        )

    normalized_vectors: list[list[float]] = []
    for item in embeddings:
        values = list(getattr(item, "values", None) or [])
        if len(values) != EMBEDDING_DIM:
            logger.error(
                "Embedding dimension mismatch: expected %d, got %d",
                EMBEDDING_DIM,
                len(values),
            )
            raise AppException(
                status_code=502,
                code="EMBEDDING_INVALID_VECTOR",
                message="Embedding vector dimension mismatch.",
            )
        normalized_vectors.append(l2_normalize(values))

    return normalized_vectors


async def generate_embedding(
    text: str,
    *,
    task_type: str = EMBEDDING_TASK_DOCUMENT,
    client: genai.Client | None = None,
) -> list[float]:
    """Generate a single L2-normalized 768-dim Gemini embedding."""
    vectors = await generate_embeddings([text], task_type=task_type, client=client)
    return vectors[0]

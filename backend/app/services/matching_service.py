"""Service facade executing the LangGraph Hybrid Matching Agent for an analysis job."""

from __future__ import annotations

import json
from typing import Any

import httpx
from google import genai

from app.agents.matching_graph import matching_graph
from app.agents.state import MatchingAgentState
from app.agents.tools.tavily_client import tavily_cache
from app.core.constants import EMBEDDING_TASK_QUERY
from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.db.repositories.match_repository import MatchRepository
from app.db.repositories.profile_repository import ProfileRepository
from app.services.embedding_service import build_profile_embedding_text, generate_embedding
from app.services.enrichment.cache import TTLCache

logger = get_logger(__name__)


def _parse_embedding(raw_embedding: Any) -> list[float]:
    """Normalize pgvector embeddings returned as either list[float] or JSON string."""
    if isinstance(raw_embedding, list):
        return [float(value) for value in raw_embedding]
    if isinstance(raw_embedding, str) and raw_embedding.strip():
        try:
            decoded = json.loads(raw_embedding)
            if isinstance(decoded, list):
                return [float(value) for value in decoded]
        except (ValueError, TypeError) as exc:
            logger.warning("Failed to parse stringified pgvector embedding: %s", exc)
    return []


class MatchingService:
    """Coordinates profile loading, embedding check, and LangGraph matching execution."""

    def __init__(
        self,
        *,
        profile_repo: ProfileRepository | None = None,
        match_repo: MatchRepository | None = None,
        gemini_client: genai.Client | None = None,
        tavily_http_client: httpx.AsyncClient | None = None,
        active_tavily_cache: TTLCache | None = None,
        graph: Any | None = None,
    ) -> None:
        self._profile_repo = profile_repo if profile_repo is not None else ProfileRepository()
        self._match_repo = match_repo if match_repo is not None else MatchRepository()
        self._gemini_client = gemini_client
        self._tavily_http_client = tavily_http_client
        self._tavily_cache = (
            active_tavily_cache if active_tavily_cache is not None else tavily_cache
        )
        self._graph = graph if graph is not None else matching_graph

    async def _load_profile(
        self,
        profile_id: str,
        user_id: str,
        profile_data: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if profile_data is not None:
            return profile_data

        profile = await self._profile_repo.get_latest_profile(user_id)
        if profile is not None and str(profile.get("id")) == profile_id:
            return profile

        if hasattr(self._profile_repo, "update_profile"):
            fetched = await self._profile_repo.update_profile(profile_id, user_id, {})
            if fetched is not None:
                return fetched

        if profile is not None:
            return profile

        raise AppException(
            status_code=404,
            code="PROFILE_NOT_FOUND",
            message="Candidate profile not found for matching.",
        )

    async def run_for_job(
        self,
        job_id: str,
        profile_id: str,
        user_id: str,
        *,
        profile_data: dict[str, Any] | None = None,
        filter_level: str | None = None,
    ) -> list[dict[str, Any]]:
        """Execute hybrid matching graph for a given (job_id, profile_id, user_id)."""
        profile = await self._load_profile(profile_id, user_id, profile_data)
        query_embedding = _parse_embedding(profile.get("embedding"))

        if not query_embedding:
            embedding_text = build_profile_embedding_text(profile)
            if embedding_text.strip():
                query_embedding = await generate_embedding(
                    embedding_text,
                    task_type=EMBEDDING_TASK_QUERY,
                    client=self._gemini_client,
                )

        initial_state: MatchingAgentState = {
            "job_id": job_id,
            "profile_id": profile_id,
            "user_id": user_id,
            "candidate_profile": profile,
            "query_embedding": query_embedding,
            "filter_level": filter_level,
            "warnings": [],
        }
        configurable: dict[str, Any] = {
            "match_repo": self._match_repo,
            "tavily_cache": self._tavily_cache,
        }
        if self._gemini_client is not None:
            configurable["gemini_client"] = self._gemini_client
        if self._tavily_http_client is not None:
            configurable["tavily_http_client"] = self._tavily_http_client

        final_state = await self._graph.ainvoke(
            initial_state,
            config={"configurable": configurable},
        )
        return list(final_state.get("saved_matches") or [])

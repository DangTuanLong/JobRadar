"""LangGraph state schema for the Hybrid Matching Agent."""

from __future__ import annotations

import operator
from typing import Annotated, Any, TypedDict


class MatchingAgentState(TypedDict, total=False):
    """Shared graph state flowing across retrieve, research, evaluate, and persist nodes."""

    job_id: str
    profile_id: str
    user_id: str
    candidate_profile: dict[str, Any]
    query_embedding: list[float]
    filter_level: str | None
    retrieved_candidates: list[dict[str, Any]]
    used_fallback_threshold: bool
    web_research: dict[str, list[dict[str, str]]]
    evaluated_matches: list[dict[str, Any]]
    saved_matches: list[dict[str, Any]]
    warnings: Annotated[list[str], operator.add]

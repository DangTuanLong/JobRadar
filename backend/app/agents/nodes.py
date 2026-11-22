"""Async LangGraph nodes and conditional routing for Hybrid Job Matching."""

from typing import Any, Literal

import httpx
from google import genai
from google.genai import types
from langchain_core.runnables import RunnableConfig
from pydantic import ValidationError

from app.agents.research_helpers import (
    backfill_top_web_evidence,
    fetch_company_evidence_batch,
)
from app.agents.state import MatchingAgentState
from app.agents.tools.tavily_client import tavily_cache
from app.core.config import settings
from app.core.constants import (
    MATCH_MAX_CANDIDATES_FOR_LLM,
    MATCH_MIN_CANDIDATES_BEFORE_FALLBACK,
    MATCH_RETRIEVE_LIMIT,
    MATCH_TAVILY_TOP_COMPANIES,
    MATCH_TOP_RESULTS_TO_SAVE,
    MATCH_VECTOR_THRESHOLD_FALLBACK,
    MATCH_VECTOR_THRESHOLD_PRIMARY,
    TAVILY_TIMEOUT_SECONDS,
)
from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.db.repositories.match_repository import MatchRepository
from app.prompts.matching import MATCHING_SYSTEM_INSTRUCTION, build_matching_user_prompt
from app.schemas.match import (
    LLMMatchBatchResponse,
    MatchResultCreate,
    WebEvidenceItem,
    compute_weighted_match_score,
)
from app.services.enrichment.cache import TTLCache
from app.services.gemini_client import (
    build_thinking_config,
    call_gemini_with_fallback,
    get_gemini_client,
)

logger = get_logger(__name__)


def _get_configurable(config: RunnableConfig | None) -> dict[str, Any]:
    configurable = config.get("configurable") if config else None
    return configurable if isinstance(configurable, dict) else {}


def _deduplicate_by_company(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Keep at most one highest-similarity posting per company, capped for LLM context."""
    ordered = sorted(
        candidates,
        key=lambda item: float(item.get("similarity") or 0.0),
        reverse=True,
    )
    selected: list[dict[str, Any]] = []
    seen_companies: set[str] = set()
    for item in ordered:
        company_id = str(item.get("company_id") or "")
        if not company_id or company_id in seen_companies:
            continue
        seen_companies.add(company_id)
        selected.append(item)
        if len(selected) >= MATCH_MAX_CANDIDATES_FOR_LLM:
            break
    return selected


def _clean_skill_list(skills: list[str]) -> list[str]:
    cleaned: list[str] = []
    seen: set[str] = set()
    for skill in skills:
        text = skill.strip()
        if text and text.lower() not in seen:
            seen.add(text.lower())
            cleaned.append(text)
    return cleaned


def _parse_batch_response(response: object) -> LLMMatchBatchResponse:
    parsed = getattr(response, "parsed", None)
    if isinstance(parsed, LLMMatchBatchResponse):
        return parsed
    if isinstance(parsed, dict):
        return LLMMatchBatchResponse.model_validate(parsed)
    raw_text = getattr(response, "text", None)
    if isinstance(raw_text, str) and raw_text.strip():
        return LLMMatchBatchResponse.model_validate_json(raw_text)
    raise ValueError("Gemini matching response contained neither parsed object nor JSON text.")


async def retrieve_candidates_node(
    state: MatchingAgentState,
    config: RunnableConfig,
) -> dict[str, Any]:
    """Retrieve matching job postings via pgvector with automatic low-threshold fallback."""
    query_embedding = state.get("query_embedding") or []
    if not query_embedding:
        logger.warning("Skipping retrieval: candidate profile has no query_embedding.")
        return {
            "retrieved_candidates": [],
            "used_fallback_threshold": False,
            "warnings": ["Candidate profile missing embedding vector."],
        }

    cfg_repo = _get_configurable(config).get("match_repo")
    match_repo: MatchRepository = cfg_repo if cfg_repo is not None else MatchRepository()
    filter_level = state.get("filter_level")
    raw_candidates = await match_repo.search_job_postings(
        query_embedding=query_embedding,
        match_threshold=MATCH_VECTOR_THRESHOLD_PRIMARY,
        match_count=MATCH_RETRIEVE_LIMIT,
        filter_level=filter_level,
    )

    used_fallback = False
    if len(raw_candidates) < MATCH_MIN_CANDIDATES_BEFORE_FALLBACK:
        logger.info(
            "Primary retrieval returned %d candidate(s) (< %d); retrying with %.2f.",
            len(raw_candidates),
            MATCH_MIN_CANDIDATES_BEFORE_FALLBACK,
            MATCH_VECTOR_THRESHOLD_FALLBACK,
        )
        raw_candidates = await match_repo.search_job_postings(
            query_embedding=query_embedding,
            match_threshold=MATCH_VECTOR_THRESHOLD_FALLBACK,
            match_count=MATCH_RETRIEVE_LIMIT,
            filter_level=filter_level,
        )
        used_fallback = True

    diverse_candidates = _deduplicate_by_company(raw_candidates)
    logger.info(
        "Retrieved %d diverse candidate company posting(s) (fallback=%s).",
        len(diverse_candidates),
        used_fallback,
    )
    return {"retrieved_candidates": diverse_candidates, "used_fallback_threshold": used_fallback}


def should_research(state: MatchingAgentState) -> Literal["research", "evaluate", "end"]:
    """Route graph execution based on candidate availability and Tavily API key presence."""
    if not state.get("retrieved_candidates"):
        logger.info("No retrieved candidates available; ending matching graph cleanly.")
        return "end"
    if settings.TAVILY_API_KEY.strip():
        return "research"
    logger.info("TAVILY_API_KEY is not configured; skipping web research step.")
    return "evaluate"


async def research_companies_node(
    state: MatchingAgentState,
    config: RunnableConfig,
) -> dict[str, Any]:
    """Concurrently fetch recent web evidence via Tavily for the Top 4 retrieved companies."""
    top_candidates = (state.get("retrieved_candidates") or [])[:MATCH_TAVILY_TOP_COMPANIES]
    cfg = _get_configurable(config)
    http_client: httpx.AsyncClient | None = cfg.get("tavily_http_client")
    cfg_cache = cfg.get("tavily_cache")
    active_cache: TTLCache = cfg_cache if cfg_cache is not None else tavily_cache
    timeout_seconds = float(cfg.get("tavily_timeout_seconds", TAVILY_TIMEOUT_SECONDS))

    web_research, warnings = await fetch_company_evidence_batch(
        top_candidates,
        active_cache=active_cache,
        http_client=http_client,
        timeout_seconds=timeout_seconds,
    )
    return {"web_research": web_research, "warnings": warnings}


async def evaluate_and_score_node(
    state: MatchingAgentState,
    config: RunnableConfig,
) -> dict[str, Any]:
    """Run a single Gemini Structured Output batch evaluation with anti-hallucination guardrails."""
    candidates = state.get("retrieved_candidates") or []
    if not candidates:
        return {"evaluated_matches": []}

    profile = state.get("candidate_profile") or {}
    web_research = state.get("web_research") or {}
    job_id = str(state.get("job_id") or "")
    profile_id = str(state.get("profile_id") or "")
    valid_by_posting_id = {
        str(c["job_posting_id"]): c for c in candidates if c.get("job_posting_id")
    }
    user_prompt = build_matching_user_prompt(profile, candidates, web_research)
    cfg_gemini = _get_configurable(config).get("gemini_client")
    gemini_client: genai.Client = cfg_gemini if cfg_gemini is not None else get_gemini_client()

    async def _call_model(model_name: str) -> Any:
        thinking = build_thinking_config(model_name, settings.GEMINI_MATCHING_THINKING_LEVEL)
        gen_config = types.GenerateContentConfig(
            system_instruction=MATCHING_SYSTEM_INSTRUCTION,
            response_mime_type="application/json",
            response_schema=LLMMatchBatchResponse,
            thinking_config=thinking,
        )
        return await gemini_client.aio.models.generate_content(
            model=model_name, contents=user_prompt, config=gen_config
        )

    response = await call_gemini_with_fallback(
        "match_evaluation",
        _call_model,
        primary_model=settings.GEMINI_MODEL_MATCHING,
        fallback_model=settings.GEMINI_MODEL_FALLBACK,
        timeout_seconds=settings.GEMINI_MATCHING_TIMEOUT_SECONDS,
    )
    try:
        batch = _parse_batch_response(response)
    except (ValidationError, ValueError) as exc:
        logger.warning("Gemini match evaluation structured output failed validation: %s", exc)
        raise AppException(
            status_code=502,
            code="MATCH_EVALUATION_FAILED",
            message="Could not parse structured match evaluations from AI model.",
        ) from exc

    evaluated_matches: list[dict[str, Any]] = []
    warnings: list[str] = []
    seen_posting_ids: set[str] = set()
    for item in batch.evaluations:
        jp_id, comp_id = item.job_posting_id.strip(), item.company_id.strip()
        retrieved = valid_by_posting_id.get(jp_id)
        if retrieved is None or str(retrieved.get("company_id") or "") != comp_id:
            msg = f"Dropped hallucinated match item (job_posting_id={jp_id}, company_id={comp_id})."
            logger.warning(msg)
            warnings.append(msg)
            continue
        if jp_id in seen_posting_ids:
            continue
        seen_posting_ids.add(jp_id)
        record = MatchResultCreate(
            job_id=job_id,
            profile_id=profile_id,
            company_id=comp_id,
            job_posting_id=jp_id,
            match_score=compute_weighted_match_score(item.score_breakdown),
            score_breakdown=item.score_breakdown,
            matched_skills=_clean_skill_list(item.matched_skills),
            missing_skills=_clean_skill_list(item.missing_skills),
            reasoning=item.reasoning.strip(),
            web_evidence=[
                WebEvidenceItem.model_validate(e) for e in web_research.get(comp_id, [])
            ],
        )
        evaluated_matches.append(record.model_dump(mode="json"))

    return {"evaluated_matches": evaluated_matches, "warnings": warnings}


async def format_and_persist_node(
    state: MatchingAgentState,
    config: RunnableConfig,
) -> dict[str, Any]:
    """Sort matches descending, backfill Top-5 Tavily web_evidence, and persist Top K results."""
    evaluated_matches = state.get("evaluated_matches") or []
    if not evaluated_matches:
        return {"saved_matches": []}

    retrieved_candidates = state.get("retrieved_candidates") or []
    sim_map = {
        str(c.get("job_posting_id")): float(c.get("similarity") or 0.0)
        for c in retrieved_candidates
    }

    def _sort_key(m: dict[str, Any]) -> tuple[int, float]:
        return int(m.get("match_score") or 0), sim_map.get(str(m.get("job_posting_id")), 0.0)

    top_matches = sorted(evaluated_matches, key=_sort_key, reverse=True)[:MATCH_TOP_RESULTS_TO_SAVE]
    cfg = _get_configurable(config)
    cfg_cache = cfg.get("tavily_cache")
    active_cache: TTLCache = cfg_cache if cfg_cache is not None else tavily_cache
    top_matches, merged_research, backfill_warnings = await backfill_top_web_evidence(
        top_matches,
        retrieved_candidates=retrieved_candidates,
        prior_web_research=state.get("web_research") or {},
        active_cache=active_cache,
        http_client=cfg.get("tavily_http_client"),
        timeout_seconds=float(cfg.get("tavily_timeout_seconds", TAVILY_TIMEOUT_SECONDS)),
    )

    cfg_repo = cfg.get("match_repo")
    match_repo: MatchRepository = cfg_repo if cfg_repo is not None else MatchRepository()
    job_id, profile_id = str(state.get("job_id") or ""), str(state.get("profile_id") or "")
    saved = await match_repo.save_match_results(
        job_id=job_id, profile_id=profile_id, results=top_matches
    )
    logger.info("Persisted %d explainable match result(s).", len(saved))
    return {"saved_matches": saved, "web_research": merged_research, "warnings": backfill_warnings}

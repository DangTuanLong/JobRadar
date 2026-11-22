"""Helper utilities for concurrent Tavily company research and Top-5 evidence backfill."""

from __future__ import annotations

import asyncio
from typing import Any

import httpx

from app.agents.tools.tavily_client import search_company_web_evidence, tavily_cache
from app.core.config import settings
from app.core.constants import MATCH_TAVILY_BACKFILL_TOP_N, TAVILY_TIMEOUT_SECONDS
from app.core.logging import get_logger
from app.schemas.match import WebEvidenceItem
from app.services.enrichment.cache import TTLCache

logger = get_logger(__name__)


async def fetch_company_evidence_batch(
    candidates: list[dict[str, Any]],
    *,
    active_cache: TTLCache | None = None,
    http_client: httpx.AsyncClient | None = None,
    timeout_seconds: float = TAVILY_TIMEOUT_SECONDS,
) -> tuple[dict[str, list[dict[str, str]]], list[str]]:
    """Concurrently query Tavily for a batch of company candidate records."""
    if not candidates:
        return {}, []

    cache = active_cache if active_cache is not None else tavily_cache

    async def _fetch_one(candidate: dict[str, Any]) -> tuple[str, list[dict[str, str]], str | None]:
        company_id = str(candidate.get("company_id") or "")
        company_name = str(candidate.get("company_name") or "").strip()
        if not company_id or not company_name:
            return company_id, [], None
        try:
            items = await asyncio.wait_for(
                search_company_web_evidence(
                    company_name,
                    cache=cache,
                    http_client=http_client,
                    timeout_seconds=timeout_seconds,
                ),
                timeout=timeout_seconds,
            )
            return company_id, items, None
        except TimeoutError:
            msg = f"Tavily research timed out for company '{company_name}'."
            logger.warning(msg)
            return company_id, [], msg
        except Exception as exc:
            msg = f"Tavily research error for company '{company_name}': {exc}"
            logger.warning(msg)
            return company_id, [], msg

    web_research: dict[str, list[dict[str, str]]] = {}
    warnings: list[str] = []
    for company_id, items, warning in await asyncio.gather(*(_fetch_one(c) for c in candidates)):
        if company_id:
            web_research[company_id] = items
        if warning:
            warnings.append(warning)
    return web_research, warnings


async def backfill_top_web_evidence(
    sorted_matches: list[dict[str, Any]],
    *,
    retrieved_candidates: list[dict[str, Any]],
    prior_web_research: dict[str, list[dict[str, str]]],
    active_cache: TTLCache | None = None,
    http_client: httpx.AsyncClient | None = None,
    timeout_seconds: float = TAVILY_TIMEOUT_SECONDS,
    top_n: int = MATCH_TAVILY_BACKFILL_TOP_N,
) -> tuple[list[dict[str, Any]], dict[str, list[dict[str, str]]], list[str]]:
    """Backfill Tavily web_evidence for Top-N scored matches missed in initial vector Top-4."""
    merged_research = dict(prior_web_research)
    if not sorted_matches or not settings.TAVILY_API_KEY.strip():
        return sorted_matches, merged_research, []

    candidate_by_company = {
        str(c.get("company_id") or ""): c
        for c in retrieved_candidates
        if c.get("company_id")
    }

    to_backfill: list[dict[str, Any]] = []
    seen_company_ids: set[str] = set()
    for match in sorted_matches[:top_n]:
        comp_id = str(match.get("company_id") or "")
        has_evidence = bool(match.get("web_evidence"))
        # Skip companies already researched (including those that failed/returned empty earlier).
        if not comp_id or has_evidence or comp_id in merged_research or comp_id in seen_company_ids:
            continue
        cand = candidate_by_company.get(comp_id)
        if cand is not None:
            seen_company_ids.add(comp_id)
            to_backfill.append(cand)

    if not to_backfill:
        return sorted_matches, merged_research, []

    logger.info(
        "Backfilling Tavily web_evidence for %d Top-%d company(s).",
        len(to_backfill),
        top_n,
    )
    backfilled_map, warnings = await fetch_company_evidence_batch(
        to_backfill,
        active_cache=active_cache,
        http_client=http_client,
        timeout_seconds=timeout_seconds,
    )
    merged_research.update(backfilled_map)

    updated_matches: list[dict[str, Any]] = []
    for idx, match in enumerate(sorted_matches):
        comp_id = str(match.get("company_id") or "")
        if idx < top_n and not match.get("web_evidence") and comp_id in backfilled_map:
            validated = [
                WebEvidenceItem.model_validate(e).model_dump(mode="json")
                for e in backfilled_map[comp_id]
            ]
            updated_matches.append({**match, "web_evidence": validated})
        else:
            updated_matches.append(match)

    return updated_matches, merged_research, warnings

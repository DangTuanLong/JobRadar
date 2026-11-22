"""Async Tavily Search HTTP client with 24h TTL caching and soft error handling."""

from __future__ import annotations

from typing import Any

import httpx

from app.core.config import settings
from app.core.constants import (
    ENRICHMENT_CACHE_MAXSIZE,
    ENRICHMENT_CACHE_TTL_SECONDS,
    TAVILY_MAX_RESULTS_PER_COMPANY,
    TAVILY_SNIPPET_MAX_CHARS,
    TAVILY_TIMEOUT_SECONDS,
)
from app.core.logging import get_logger
from app.schemas.profile import normalize_url
from app.services.enrichment.cache import TTLCache

logger = get_logger(__name__)

TAVILY_SEARCH_URL = "https://api.tavily.com/search"
USER_AGENT_HEADER = "JobRadar-Backend"

tavily_cache = TTLCache(
    maxsize=ENRICHMENT_CACHE_MAXSIZE,
    ttl_seconds=ENRICHMENT_CACHE_TTL_SECONDS,
)


def make_tavily_cache_key(company_name: str) -> str:
    """Build a normalized cache key for company web research."""
    normalized = " ".join(company_name.strip().lower().split())
    return f"tavily:{normalized}"


def _build_company_query(company_name: str) -> str:
    clean_name = " ".join(company_name.strip().split())
    return f'"{clean_name}" engineering tech stack culture careers Vietnam'


def _extract_evidence_items(
    raw_results: Any,
    *,
    max_results: int,
) -> list[dict[str, str]]:
    if not isinstance(raw_results, list):
        return []

    evidence: list[dict[str, str]] = []
    seen_urls: set[str] = set()
    for item in raw_results:
        if not isinstance(item, dict):
            continue
        normalized_url = normalize_url(item.get("url"))
        if not normalized_url or normalized_url in seen_urls:
            continue

        title = str(item.get("title") or "").strip() or normalized_url
        content = " ".join(str(item.get("content") or "").split())
        snippet = content[:TAVILY_SNIPPET_MAX_CHARS]
        seen_urls.add(normalized_url)
        evidence.append(
            {
                "title": title,
                "url": normalized_url,
                "snippet": snippet,
            }
        )
        if len(evidence) >= max_results:
            break

    return evidence


async def search_company_web_evidence(
    company_name: str,
    *,
    api_key: str | None = None,
    cache: TTLCache | None = None,
    http_client: httpx.AsyncClient | None = None,
    timeout_seconds: float = TAVILY_TIMEOUT_SECONDS,
    max_results: int = TAVILY_MAX_RESULTS_PER_COMPANY,
) -> list[dict[str, str]]:
    """Query Tavily for recent company tech/culture evidence with 24h TTL caching."""
    clean_name = company_name.strip()
    if not clean_name:
        return []

    active_api_key = (api_key if api_key is not None else settings.TAVILY_API_KEY).strip()
    if not active_api_key:
        logger.info("Skipping Tavily search for '%s': TAVILY_API_KEY not configured.", clean_name)
        return []

    active_cache = cache if cache is not None else tavily_cache
    cache_key = make_tavily_cache_key(clean_name)
    cached = active_cache.get(cache_key)
    if cached is not None:
        return list(cached)

    payload = {
        "api_key": active_api_key,
        "query": _build_company_query(clean_name),
        "search_depth": "basic",
        "max_results": max_results,
        "include_answer": False,
    }

    owns_client = http_client is None
    client = (
        http_client
        if http_client is not None
        else httpx.AsyncClient(timeout=timeout_seconds)
    )
    try:
        response = await client.post(
            TAVILY_SEARCH_URL,
            json=payload,
            headers={
                "Content-Type": "application/json",
                "User-Agent": USER_AGENT_HEADER,
            },
        )
        if response.status_code in {401, 403, 429}:
            logger.warning(
                "Tavily search returned HTTP %d for company='%s'.",
                response.status_code,
                clean_name,
            )
            return []
        if response.status_code != 200:
            logger.warning(
                "Tavily search failed with HTTP %d for company='%s'.",
                response.status_code,
                clean_name,
            )
            return []

        data = response.json()
        evidence = _extract_evidence_items(data.get("results"), max_results=max_results)
        active_cache.set(cache_key, evidence)
        return evidence
    except (httpx.TimeoutException, httpx.HTTPError, ValueError) as exc:
        logger.warning("Tavily search error for company='%s': %s", clean_name, exc)
        return []
    finally:
        if owns_client:
            await client.aclose()

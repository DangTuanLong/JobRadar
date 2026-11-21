"""Async Jina Reader client with strict SSRF protection and LinkedIn blocking."""

from __future__ import annotations

import ipaddress
from typing import Literal
from urllib.parse import urlsplit

import httpx
from pydantic import BaseModel

from app.core.constants import (
    ENRICHMENT_SOURCE_TIMEOUT_SECONDS,
    PORTFOLIO_MAX_CHARS,
    PORTFOLIO_SUMMARY_MAX_CHARS,
)
from app.core.logging import get_logger
from app.schemas.profile import normalize_url
from app.services.enrichment.cache import TTLCache, enrichment_cache, make_jina_cache_key

logger = get_logger(__name__)

JINA_READER_BASE = "https://r.jina.ai/"
USER_AGENT_HEADER = "JobRadar-Backend"

_BLOCKED_HOSTNAMES = frozenset({"localhost", "localhost.localdomain", "0.0.0.0"})
_BLOCKED_HOST_SUFFIXES = (".localhost", ".local", ".internal")


class JinaEnrichmentData(BaseModel):
    """Structured enrichment result collected from Jina Reader."""

    status: Literal["success", "failed", "skipped"]
    url: str | None = None
    markdown_content: str = ""
    summary_excerpt: str | None = None
    error: str | None = None


def _is_disallowed_ip_literal(host: str) -> bool:
    """Return True if host represents an IP literal in a restricted/private range."""
    clean_host = host.strip("[]")
    try:
        ip_obj = ipaddress.ip_address(clean_host)
    except ValueError:
        # Block decimal/hex integer IP aliases (e.g., 2130706433 or 0x7f000001).
        if clean_host.isdigit() or clean_host.lower().startswith("0x"):
            return True
        return False

    return (
        ip_obj.is_private
        or ip_obj.is_loopback
        or ip_obj.is_link_local
        or ip_obj.is_reserved
        or ip_obj.is_multicast
        or ip_obj.is_unspecified
    )


def validate_safe_public_url(raw_url: str | None) -> tuple[bool, str]:
    """Validate URL for SSRF safety and block all LinkedIn domains."""
    if not raw_url or not isinstance(raw_url, str):
        return False, "Empty URL."

    raw_stripped = raw_url.strip()
    if "://" in raw_stripped:
        raw_scheme = raw_stripped.split("://", maxsplit=1)[0].lower()
        if raw_scheme not in {"http", "https"}:
            return False, f"Blocked non-HTTP(S) URL scheme: {raw_scheme}."

    normalized = normalize_url(raw_stripped)
    if not normalized:
        return False, "Invalid or unsupported URL."

    parsed = urlsplit(normalized)
    if parsed.username or parsed.password:
        return False, "URLs containing credentials are not allowed."

    host = (parsed.hostname or "").lower().rstrip(".")
    if not host:
        return False, "URL missing hostname."

    if host in _BLOCKED_HOSTNAMES or any(host.endswith(sfx) for sfx in _BLOCKED_HOST_SUFFIXES):
        return False, f"Blocked local/internal hostname: {host}."

    if _is_disallowed_ip_literal(host):
        return False, f"Blocked private or restricted IP address: {host}."

    if host == "linkedin.com" or host.endswith(".linkedin.com"):
        return False, "LinkedIn URLs are blocked by project scraping guardrails."

    return True, normalized


async def fetch_jina_portfolio(
    portfolio_url: str | None,
    *,
    cache: TTLCache | None = None,
    http_client: httpx.AsyncClient | None = None,
    timeout_seconds: float = ENRICHMENT_SOURCE_TIMEOUT_SECONDS,
) -> JinaEnrichmentData:
    """Fetch clean Markdown content of a safe public portfolio URL via Jina Reader."""
    if not portfolio_url:
        return JinaEnrichmentData(status="skipped")

    is_safe, value_or_reason = validate_safe_public_url(portfolio_url)
    if not is_safe:
        logger.warning("Blocked unsafe portfolio URL '%s': %s", portfolio_url, value_or_reason)
        return JinaEnrichmentData(
            status="failed",
            url=portfolio_url,
            error=value_or_reason,
        )

    normalized_url = value_or_reason
    active_cache = cache if cache is not None else enrichment_cache
    cache_key = make_jina_cache_key(normalized_url)
    cached = active_cache.get(cache_key)
    if cached is not None:
        return JinaEnrichmentData.model_validate(cached)

    owns_client = http_client is None
    client = (
        http_client
        if http_client is not None
        else httpx.AsyncClient(timeout=timeout_seconds)
    )
    try:
        response = await client.get(
            f"{JINA_READER_BASE}{normalized_url}",
            headers={
                "Accept": "text/markdown",
                "User-Agent": USER_AGENT_HEADER,
            },
        )
        if response.status_code != 200:
            logger.warning(
                "Jina Reader returned HTTP %d for url=%s",
                response.status_code,
                normalized_url,
            )
            return JinaEnrichmentData(
                status="failed",
                url=normalized_url,
                error=f"Jina Reader HTTP {response.status_code}.",
            )

        markdown = response.text.strip()[:PORTFOLIO_MAX_CHARS]
        if not markdown:
            return JinaEnrichmentData(
                status="failed",
                url=normalized_url,
                error="Portfolio page returned empty content.",
            )

        collapsed = " ".join(markdown.split())
        summary_excerpt = collapsed[:PORTFOLIO_SUMMARY_MAX_CHARS]
        result = JinaEnrichmentData(
            status="success",
            url=normalized_url,
            markdown_content=markdown,
            summary_excerpt=summary_excerpt,
        )
        active_cache.set(cache_key, result.model_dump())
        return result
    except (httpx.TimeoutException, httpx.HTTPError) as exc:
        logger.warning("Jina Reader request failed for url=%s: %s", normalized_url, exc)
        return JinaEnrichmentData(
            status="failed",
            url=normalized_url,
            error=f"Jina Reader request failed: {exc}",
        )
    finally:
        if owns_client:
            await client.aclose()

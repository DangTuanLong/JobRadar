"""Async GitHub REST API client for developer profile and repository enrichment."""

from __future__ import annotations

import asyncio
import re
from typing import Any, Literal
from urllib.parse import urlsplit

import httpx
from pydantic import BaseModel, Field

from app.core.config import settings
from app.core.constants import (
    ENRICHMENT_SOURCE_TIMEOUT_SECONDS,
    GITHUB_MAX_REPOS_FETCH,
    GITHUB_README_MAX_CHARS,
    GITHUB_TOP_REPOS_LIMIT,
)
from app.core.logging import get_logger
from app.schemas.profile import normalize_url
from app.services.enrichment.cache import TTLCache, enrichment_cache, make_github_cache_key

logger = get_logger(__name__)

GITHUB_API_BASE = "https://api.github.com"
USER_AGENT_HEADER = "JobRadar-Backend"

_SYSTEM_GITHUB_PATHS = frozenset(
    {
        "about",
        "apps",
        "codespaces",
        "collections",
        "copilot",
        "enterprise",
        "events",
        "explore",
        "features",
        "issues",
        "join",
        "login",
        "marketplace",
        "notifications",
        "orgs",
        "pricing",
        "pulls",
        "search",
        "security",
        "settings",
        "signup",
        "sponsors",
        "topics",
        "trending",
    }
)
_GITHUB_USERNAME_RE = re.compile(r"^[a-zA-Z0-9](?:[a-zA-Z0-9]|-(?=[a-zA-Z0-9])){0,38}$")


class GitHubEnrichmentData(BaseModel):
    """Structured enrichment result collected from GitHub REST API."""

    status: Literal["success", "failed", "skipped"]
    username: str | None = None
    public_repos: int = 0
    total_stars: int = 0
    top_languages: list[str] = Field(default_factory=list)
    topics: list[str] = Field(default_factory=list)
    highlights: list[str] = Field(default_factory=list)
    readme_excerpts: list[str] = Field(default_factory=list)
    error: str | None = None


def extract_github_username(raw_url: str | None) -> str | None:
    """Safely extract a GitHub username from a profile or repository URL."""
    normalized = normalize_url(raw_url)
    if not normalized:
        return None
    parsed = urlsplit(normalized)
    host = (parsed.hostname or "").lower()
    if host not in {"github.com", "www.github.com"}:
        return None

    segments = [seg for seg in parsed.path.split("/") if seg]
    if not segments:
        return None

    username = segments[0]
    if username.lower() in _SYSTEM_GITHUB_PATHS:
        return None
    if not _GITHUB_USERNAME_RE.match(username):
        return None
    return username


def _build_github_headers(*, raw_readme: bool = False) -> dict[str, str]:
    accept_header = (
        "application/vnd.github.raw+json"
        if raw_readme
        else "application/vnd.github+json"
    )
    headers = {
        "Accept": accept_header,
        "User-Agent": USER_AGENT_HEADER,
    }
    if settings.GITHUB_TOKEN:
        headers["Authorization"] = f"Bearer {settings.GITHUB_TOKEN}"
    return headers


async def _fetch_repo_readme(
    client: httpx.AsyncClient,
    username: str,
    repo_name: str,
) -> str:
    url = f"{GITHUB_API_BASE}/repos/{username}/{repo_name}/readme"
    try:
        response = await client.get(url, headers=_build_github_headers(raw_readme=True))
    except httpx.HTTPError as exc:
        logger.warning("Failed fetching README for %s/%s: %s", username, repo_name, exc)
        return ""

    if response.status_code != 200:
        return ""
    text = response.text.strip()
    return text[:GITHUB_README_MAX_CHARS]


async def fetch_github_enrichment(
    github_url: str | None,
    *,
    cache: TTLCache | None = None,
    http_client: httpx.AsyncClient | None = None,
    timeout_seconds: float = ENRICHMENT_SOURCE_TIMEOUT_SECONDS,
) -> GitHubEnrichmentData:
    """Fetch public profile, top repositories, and README excerpts from GitHub."""
    if not github_url:
        return GitHubEnrichmentData(status="skipped")

    username = extract_github_username(github_url)
    if not username:
        logger.warning("Skipping invalid or non-user GitHub URL: %s", github_url)
        return GitHubEnrichmentData(status="skipped", error="Invalid GitHub profile URL.")

    active_cache = cache if cache is not None else enrichment_cache
    cache_key = make_github_cache_key(username)
    cached = active_cache.get(cache_key)
    if cached is not None:
        return GitHubEnrichmentData.model_validate(cached)

    owns_client = http_client is None
    client = (
        http_client
        if http_client is not None
        else httpx.AsyncClient(timeout=timeout_seconds)
    )
    try:
        user_resp = await client.get(
            f"{GITHUB_API_BASE}/users/{username}",
            headers=_build_github_headers(),
        )
        if user_resp.status_code == 404:
            logger.warning("GitHub user not found: %s", username)
            return GitHubEnrichmentData(
                status="failed",
                username=username,
                error="GitHub user not found.",
            )
        if user_resp.status_code in {403, 429}:
            logger.warning(
                "GitHub API rate limit (%d) for user=%s",
                user_resp.status_code,
                username,
            )
            return GitHubEnrichmentData(
                status="failed",
                username=username,
                error="GitHub API rate limit exceeded.",
            )
        if user_resp.status_code != 200:
            logger.warning("GitHub API returned %d for user=%s", user_resp.status_code, username)
            return GitHubEnrichmentData(
                status="failed",
                username=username,
                error=f"GitHub API error ({user_resp.status_code}).",
            )

        user_data: dict[str, Any] = user_resp.json()
        repos_resp = await client.get(
            f"{GITHUB_API_BASE}/users/{username}/repos",
            params={"sort": "pushed", "per_page": GITHUB_MAX_REPOS_FETCH, "type": "owner"},
            headers=_build_github_headers(),
        )
        repos_data: list[dict[str, Any]] = (
            repos_resp.json() if repos_resp.status_code == 200 else []
        )
        non_fork_repos = [
            repo for repo in repos_data if isinstance(repo, dict) and not repo.get("fork", False)
        ]
        non_fork_repos.sort(
            key=lambda r: (int(r.get("stargazers_count") or 0), str(r.get("pushed_at") or "")),
            reverse=True,
        )
        top_repos = non_fork_repos[:GITHUB_TOP_REPOS_LIMIT]

        readme_tasks = [
            _fetch_repo_readme(client, username, str(repo.get("name")))
            for repo in top_repos
            if repo.get("name")
        ]
        readme_results = (
            await asyncio.gather(*readme_tasks, return_exceptions=True) if readme_tasks else []
        )
        readme_excerpts = [
            res for res in readme_results if isinstance(res, str) and res.strip()
        ]

        languages: list[str] = []
        topics: list[str] = []
        highlights: list[str] = []
        seen_langs: set[str] = set()
        seen_topics: set[str] = set()

        for repo in top_repos:
            lang = repo.get("language")
            if isinstance(lang, str) and lang.strip():
                clean_lang = lang.strip()
                if clean_lang.lower() not in seen_langs:
                    seen_langs.add(clean_lang.lower())
                    languages.append(clean_lang)

            for topic in repo.get("topics") or []:
                if isinstance(topic, str) and topic.strip():
                    clean_topic = topic.strip()
                    if clean_topic.lower() not in seen_topics:
                        seen_topics.add(clean_topic.lower())
                        topics.append(clean_topic)

            repo_name = str(repo.get("name") or "repo")
            stars = int(repo.get("stargazers_count") or 0)
            desc = str(repo.get("description") or "").strip()
            lang_label = str(repo.get("language") or "Unknown")
            summary = f"{repo_name} ({lang_label}, {stars}★)"
            if desc:
                summary = f"{summary}: {desc[:120]}"
            highlights.append(summary)

        total_stars = sum(int(r.get("stargazers_count") or 0) for r in non_fork_repos)
        public_repos = int(user_data.get("public_repos") or len(non_fork_repos))
        result = GitHubEnrichmentData(
            status="success",
            username=username,
            public_repos=public_repos,
            total_stars=total_stars,
            top_languages=languages,
            topics=topics,
            highlights=highlights,
            readme_excerpts=readme_excerpts,
        )
        active_cache.set(cache_key, result.model_dump())
        return result
    except (httpx.TimeoutException, httpx.HTTPError, ValueError) as exc:
        logger.warning("GitHub enrichment failed for user=%s: %s", username, exc)
        return GitHubEnrichmentData(
            status="failed",
            username=username,
            error=f"GitHub request failed: {exc}",
        )
    finally:
        if owns_client:
            await client.aclose()

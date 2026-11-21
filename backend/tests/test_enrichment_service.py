"""Offline unit tests for GitHub/Jina enrichment, cross-verification, SSRF, and caching."""

from __future__ import annotations

import json

import httpx
import pytest

from app.schemas.profile import CandidateProfileExtracted, ExternalLinks, SkillItem
from app.services.enrichment.cache import TTLCache
from app.services.enrichment.enrichment_service import enrich_profile
from app.services.enrichment.github_client import extract_github_username
from app.services.enrichment.jina_client import fetch_jina_portfolio, validate_safe_public_url


def test_extract_github_username_handles_profiles_repos_and_system_routes() -> None:
    assert extract_github_username("https://github.com/example-dev") == "example-dev"
    assert extract_github_username("https://github.com/example-dev/jobradar") == "example-dev"
    assert extract_github_username("https://github.com/orgs/google") is None
    assert extract_github_username("https://github.com/topics/python") is None
    assert extract_github_username("https://gitlab.com/example-dev") is None


@pytest.mark.parametrize(
    "unsafe_url",
    [
        "http://localhost:8000",
        "http://127.0.0.1/internal",
        "http://192.168.1.10",
        "http://169.254.169.254/latest/meta-data",
        "file:///etc/passwd",
        "https://linkedin.com/in/test",
        "https://www.linkedin.com/in/test",
    ],
)
@pytest.mark.asyncio
async def test_ssrf_and_linkedin_urls_are_blocked_before_http_call(unsafe_url: str) -> None:
    called = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal called
        called += 1
        return httpx.Response(200, text="should never execute")

    is_safe, _ = validate_safe_public_url(unsafe_url)
    assert is_safe is False

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await fetch_jina_portfolio(unsafe_url, cache=TTLCache(), http_client=client)

    assert result.status == "failed"
    assert called == 0


@pytest.mark.asyncio
async def test_enrich_profile_cross_verifies_github_skills_and_caches_24h() -> None:
    http_calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        http_calls.append(url)
        if url == "https://api.github.com/users/example-dev":
            return httpx.Response(200, json={"login": "example-dev", "public_repos": 12})
        if url.startswith("https://api.github.com/users/example-dev/repos"):
            return httpx.Response(
                200,
                json=[
                    {
                        "name": "jobradar-api",
                        "fork": False,
                        "language": "Python",
                        "stargazers_count": 15,
                        "pushed_at": "2026-10-01T00:00:00Z",
                        "topics": ["fastapi", "docker"],
                        "description": "Async backend with PostgreSQL",
                    },
                    {
                        "name": "portfolio-web",
                        "fork": False,
                        "language": "TypeScript",
                        "stargazers_count": 4,
                        "pushed_at": "2026-09-01T00:00:00Z",
                        "topics": [],
                        "description": "Personal site",
                    },
                    {
                        "name": "forked-demo",
                        "fork": True,
                        "language": "Ruby",
                        "stargazers_count": 99,
                    },
                ],
            )
        if url.endswith("/readme"):
            return httpx.Response(200, text="# JobRadar\nBuilt with FastAPI and Supabase.")
        if url == "https://r.jina.ai/https://example-dev.vercel.app":
            return httpx.Response(200, text="# Portfolio\nBackend engineer building AI apps.")
        return httpx.Response(404, json={"message": "Not Found"})

    profile = CandidateProfileExtracted(
        full_name="Example Dev",
        headline="Backend Engineer",
        years_of_experience=2.0,
        skills=[
            SkillItem(name="py", category="language", level="advanced", verified_by=["cv"]),
            SkillItem(name="FastAPI", category="framework", level="advanced", verified_by=["cv"]),
            SkillItem(name="Figma", category="tool", level="beginner", verified_by=["cv"]),
        ],
        external_links=ExternalLinks(
            github="https://github.com/example-dev",
            portfolio="https://example-dev.vercel.app",
        ),
        profile_summary="Backend developer using Python and FastAPI.",
    )
    cache = TTLCache(maxsize=10, ttl_seconds=86400)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        first = await enrich_profile(profile, cache=cache, http_client=client)
        calls_after_first = len(http_calls)
        second = await enrich_profile(profile, cache=cache, http_client=client)

    assert calls_after_first > 0
    assert len(http_calls) == calls_after_first  # 2nd run served completely from 24h TTL cache.

    skills_by_name = {s.name.lower(): s for s in first.profile.skills}
    assert skills_by_name["py"].verified_by == ["cv", "github"]
    assert skills_by_name["fastapi"].verified_by == ["cv", "github"]
    assert skills_by_name["figma"].verified_by == ["cv"]
    assert "typescript" in skills_by_name
    assert skills_by_name["typescript"].verified_by == ["github"]
    assert "docker" in skills_by_name
    assert "postgresql" in skills_by_name
    assert "ruby" not in skills_by_name  # Forked repo ignored.

    assert first.enrichment_summary.source_statuses == {"github": "success", "portfolio": "success"}
    assert first.enrichment_summary.github_public_repos == 12
    assert first.enrichment_summary.github_total_stars == 19
    assert first.enrichment_summary.portfolio_summary is not None
    assert second.enrichment_summary == first.enrichment_summary


@pytest.mark.asyncio
async def test_enrich_profile_partial_failure_resilience() -> None:
    def github_fail_portfolio_ok(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        if "api.github.com" in url:
            return httpx.Response(404, text=json.dumps({"message": "Not Found"}))
        return httpx.Response(200, text="Portfolio Markdown content for candidate.")

    def github_ok_portfolio_timeout(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        if url == "https://api.github.com/users/example-dev":
            return httpx.Response(200, json={"login": "example-dev", "public_repos": 1})
        if url.startswith("https://api.github.com/users/example-dev/repos"):
            return httpx.Response(
                200,
                json=[{"name": "app", "fork": False, "language": "Kotlin", "stargazers_count": 2}],
            )
        if url.endswith("/readme"):
            return httpx.Response(404)
        raise httpx.ReadTimeout("Jina timed out", request=request)

    profile = CandidateProfileExtracted(
        skills=[
            SkillItem(
                name="Kotlin",
                category="language",
                level="intermediate",
                verified_by=["cv"],
            )
        ],
        external_links=ExternalLinks(
            github="https://github.com/example-dev",
            portfolio="https://example-dev.dev",
        ),
        profile_summary="Mobile engineer.",
    )

    async with httpx.AsyncClient(transport=httpx.MockTransport(github_fail_portfolio_ok)) as c1:
        res1 = await enrich_profile(profile, cache=TTLCache(), http_client=c1)
    assert res1.enrichment_summary.source_statuses == {"github": "failed", "portfolio": "success"}
    assert res1.enrichment_summary.portfolio_summary is not None
    assert res1.profile.skills[0].verified_by == ["cv"]

    async with httpx.AsyncClient(transport=httpx.MockTransport(github_ok_portfolio_timeout)) as c2:
        res2 = await enrich_profile(profile, cache=TTLCache(), http_client=c2)
    assert res2.enrichment_summary.source_statuses == {"github": "success", "portfolio": "failed"}
    assert res2.profile.skills[0].verified_by == ["cv", "github"]


def test_ttl_cache_evicts_oldest_when_full() -> None:
    cache = TTLCache(maxsize=2, ttl_seconds=60)
    cache.set("k1", {"v": 1})
    cache.set("k2", {"v": 2})
    cache.set("k3", {"v": 3})

    assert cache.get("k1") is None
    assert cache.get("k2") == {"v": 2}
    assert cache.get("k3") == {"v": 3}

"""Parallel enrichment orchestrator and CV-vs-GitHub skill cross-verification."""

from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass

import httpx

from app.core.constants import ENRICHMENT_SOURCE_TIMEOUT_SECONDS, VerificationSource
from app.core.logging import get_logger
from app.schemas.profile import (
    CandidateProfileExtracted,
    EnrichmentSummary,
    SkillCategory,
    SkillItem,
)
from app.services.enrichment.cache import TTLCache
from app.services.enrichment.github_client import GitHubEnrichmentData, fetch_github_enrichment
from app.services.enrichment.jina_client import JinaEnrichmentData, fetch_jina_portfolio

logger = get_logger(__name__)

# Canonical skill table: normalized key -> (Display Name, SkillCategory)
_CANONICAL_SKILLS: dict[str, tuple[str, SkillCategory]] = {
    "python": ("Python", "language"),
    "py": ("Python", "language"),
    "kotlin": ("Kotlin", "language"),
    "java": ("Java", "language"),
    "javascript": ("JavaScript", "language"),
    "js": ("JavaScript", "language"),
    "typescript": ("TypeScript", "language"),
    "ts": ("TypeScript", "language"),
    "dart": ("Dart", "language"),
    "go": ("Go", "language"),
    "golang": ("Go", "language"),
    "rust": ("Rust", "language"),
    "c++": ("C++", "language"),
    "cpp": ("C++", "language"),
    "c#": ("C#", "language"),
    "csharp": ("C#", "language"),
    "swift": ("Swift", "language"),
    "sql": ("SQL", "database"),
    "fastapi": ("FastAPI", "framework"),
    "django": ("Django", "framework"),
    "flask": ("Flask", "framework"),
    "spring boot": ("Spring Boot", "framework"),
    "springboot": ("Spring Boot", "framework"),
    "jetpack compose": ("Jetpack Compose", "framework"),
    "compose": ("Jetpack Compose", "framework"),
    "android": ("Android", "framework"),
    "flutter": ("Flutter", "framework"),
    "react": ("React", "framework"),
    "reactjs": ("React", "framework"),
    "next.js": ("Next.js", "framework"),
    "nextjs": ("Next.js", "framework"),
    "node.js": ("Node.js", "framework"),
    "nodejs": ("Node.js", "framework"),
    "postgresql": ("PostgreSQL", "database"),
    "postgres": ("PostgreSQL", "database"),
    "supabase": ("Supabase", "database"),
    "mongodb": ("MongoDB", "database"),
    "redis": ("Redis", "database"),
    "docker": ("Docker", "cloud_devops"),
    "kubernetes": ("Kubernetes", "cloud_devops"),
    "k8s": ("Kubernetes", "cloud_devops"),
    "terraform": ("Terraform", "cloud_devops"),
    "github actions": ("GitHub Actions", "cloud_devops"),
    "langchain": ("LangChain", "ai_ml"),
    "langgraph": ("LangGraph", "ai_ml"),
    "pytorch": ("PyTorch", "ai_ml"),
    "tensorflow": ("TensorFlow", "ai_ml"),
    "tf": ("TensorFlow", "ai_ml"),
    "tailwind css": ("Tailwind CSS", "framework"),
    "tailwindcss": ("Tailwind CSS", "framework"),
    "git": ("Git", "tool"),
}


@dataclass(slots=True)
class EnrichedProfileResult:
    """Combined enriched candidate profile and summary metadata."""

    profile: CandidateProfileExtracted
    enrichment_summary: EnrichmentSummary


def _canonicalize_skill_key(name: str) -> str:
    """Normalize skill names and resolve common developer aliases."""
    token = " ".join(name.strip().lower().replace("_", " ").replace("-", " ").split())
    if token in _CANONICAL_SKILLS:
        return _CANONICAL_SKILLS[token][0].lower()
    compact = token.replace(" ", "")
    if compact in _CANONICAL_SKILLS:
        return _CANONICAL_SKILLS[compact][0].lower()
    return token


def _extract_github_skill_evidence(
    github_data: GitHubEnrichmentData,
) -> tuple[set[str], list[tuple[str, SkillCategory]]]:
    """Collect canonical skill keys verified via GitHub languages, topics, and READMEs."""
    verified_keys: set[str] = set()
    discovered: dict[str, tuple[str, SkillCategory]] = {}

    for lang in github_data.top_languages:
        canonical = _canonicalize_skill_key(lang)
        verified_keys.add(canonical)
        display_name, category = _CANONICAL_SKILLS.get(canonical, (lang.strip(), "language"))
        discovered.setdefault(canonical, (display_name, category))

    for topic in github_data.topics:
        canonical = _canonicalize_skill_key(topic)
        verified_keys.add(canonical)
        if canonical in _CANONICAL_SKILLS:
            discovered.setdefault(canonical, _CANONICAL_SKILLS[canonical])

    corpus = "\n".join([*github_data.highlights, *github_data.readme_excerpts]).lower()
    if corpus:
        for alias, (display_name, category) in _CANONICAL_SKILLS.items():
            # Skip short ambiguous abbreviations (js/ts/py/tf/go) inside free-text README scan.
            if len(alias) <= 2:
                continue
            pattern = rf"(?<![a-z0-9]){re.escape(alias)}(?![a-z0-9])"
            if re.search(pattern, corpus):
                canonical = display_name.lower()
                verified_keys.add(canonical)
                discovered.setdefault(canonical, (display_name, category))

    return verified_keys, list(discovered.values())


def cross_verify_skills(
    skills: list[SkillItem],
    github_data: GitHubEnrichmentData,
) -> list[SkillItem]:
    """Cross-verify CV skills against GitHub evidence and append newly discovered skills."""
    if github_data.status != "success":
        return [skill.model_copy(deep=True) for skill in skills]

    verified_keys, discovered_skills = _extract_github_skill_evidence(github_data)
    updated_skills: list[SkillItem] = []
    existing_keys: set[str] = set()

    for skill in skills:
        canonical = _canonicalize_skill_key(skill.name)
        existing_keys.add(canonical)
        verified_by = list(skill.verified_by)
        if canonical in verified_keys and VerificationSource.GITHUB.value not in verified_by:
            verified_by.append(VerificationSource.GITHUB.value)
        updated_skills.append(
            SkillItem(
                name=skill.name,
                category=skill.category,
                level=skill.level,
                verified_by=verified_by,
            )
        )

    for display_name, category in discovered_skills:
        canonical = display_name.lower()
        if canonical in existing_keys:
            continue
        existing_keys.add(canonical)
        updated_skills.append(
            SkillItem(
                name=display_name,
                category=category,
                level="intermediate",
                verified_by=[VerificationSource.GITHUB.value],
            )
        )

    return updated_skills


async def _run_with_timeout(
    source_name: str,
    coro: asyncio.Future[GitHubEnrichmentData] | asyncio.Future[JinaEnrichmentData] | object,
    timeout_seconds: float,
) -> GitHubEnrichmentData | JinaEnrichmentData | Exception:
    try:
        return await asyncio.wait_for(coro, timeout=timeout_seconds)  # type: ignore[arg-type]
    except TimeoutError:
        logger.warning("%s enrichment timed out after %.1fs.", source_name, timeout_seconds)
        return TimeoutError(f"{source_name} timed out after {timeout_seconds}s.")


async def enrich_profile(
    profile: CandidateProfileExtracted,
    *,
    has_linkedin_pdf: bool = False,
    cache: TTLCache | None = None,
    http_client: httpx.AsyncClient | None = None,
    timeout_seconds: float = ENRICHMENT_SOURCE_TIMEOUT_SECONDS,
) -> EnrichedProfileResult:
    """Enrich an extracted profile concurrently from GitHub and Portfolio URLs."""
    github_url = profile.external_links.github
    portfolio_url = profile.external_links.portfolio

    github_task = _run_with_timeout(
        "github",
        fetch_github_enrichment(
            github_url,
            cache=cache,
            http_client=http_client,
            timeout_seconds=timeout_seconds,
        ),
        timeout_seconds,
    )
    portfolio_task = _run_with_timeout(
        "portfolio",
        fetch_jina_portfolio(
            portfolio_url,
            cache=cache,
            http_client=http_client,
            timeout_seconds=timeout_seconds,
        ),
        timeout_seconds,
    )

    github_raw, portfolio_raw = await asyncio.gather(
        github_task,
        portfolio_task,
        return_exceptions=True,
    )

    if isinstance(github_raw, BaseException):
        github_data = GitHubEnrichmentData(status="failed", error=str(github_raw))
    else:
        github_data = github_raw  # type: ignore[assignment]

    if isinstance(portfolio_raw, BaseException):
        portfolio_data = JinaEnrichmentData(status="failed", error=str(portfolio_raw))
    else:
        portfolio_data = portfolio_raw  # type: ignore[assignment]

    verified_skills = cross_verify_skills(profile.skills, github_data)
    enriched_profile = profile.model_copy(update={"skills": verified_skills})

    source_statuses = {
        "github": github_data.status,
        "portfolio": portfolio_data.status,
    }
    sources_fetched: list[str] = []
    if github_data.status == "success":
        sources_fetched.append(VerificationSource.GITHUB.value)
    if portfolio_data.status == "success":
        sources_fetched.append(VerificationSource.PORTFOLIO.value)
    if has_linkedin_pdf:
        sources_fetched.append(VerificationSource.LINKEDIN_PDF.value)

    fetch_warnings: list[str] = []
    if github_data.status == "failed" and github_data.error:
        fetch_warnings.append(f"GitHub: {github_data.error}")
    if portfolio_data.status == "failed" and portfolio_data.error:
        fetch_warnings.append(f"Portfolio: {portfolio_data.error}")

    summary = EnrichmentSummary(
        github_username=github_data.username,
        github_public_repos=github_data.public_repos,
        github_total_stars=github_data.total_stars,
        github_top_languages=github_data.top_languages,
        github_highlights=github_data.highlights,
        portfolio_summary=portfolio_data.summary_excerpt,
        linkedin_verified=has_linkedin_pdf,
        sources_fetched=sources_fetched,
        source_statuses=source_statuses,
        fetch_warnings=fetch_warnings,
    )
    return EnrichedProfileResult(profile=enriched_profile, enrichment_summary=summary)

"""End-to-End live integration test CLI for the full JobRadar Backend pipeline.

Runs live Gemini, GitHub, Jina Reader, Supabase pgvector, and Tavily calls.
Defaults to dry-run mode (queries live Supabase RPC without writing DB rows).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import pymupdf
from dotenv import load_dotenv

ROOT_DIR = Path(__file__).resolve().parent.parent
BACKEND_DIR = ROOT_DIR / "backend"
SAMPLE_GITHUB_URL = "https://github.com/chrisbanes"
MIME_BY_SUFFIX = {".pdf": "application/pdf", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg"}

logger = logging.getLogger("e2e_test_pipeline")


def _configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
        datefmt="%H:%M:%S",
    )
    for noisy in ("httpx", "httpcore", "hpack"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def _generate_sample_cv_pdf() -> bytes:
    """Create a 1-page sample Android Developer CV PDF with hidden GitHub link."""
    doc = pymupdf.open()
    page = doc.new_page()
    lines = [
        "SAMPLE CANDIDATE - FRESHER ANDROID DEVELOPER",
        "Headline: Fresher Android Developer (Kotlin & Jetpack Compose)",
        "Experience: 0.5 years (Android Intern & Mobile Projects)",
        "Skills: Kotlin, Jetpack Compose, Coroutines, Retrofit, MVVM, Git",
        "Project: JobRadar Mobile - Clean MVVM + Compose REST client app.",
        "Project: Compose Samples - StateFlow, Hilt DI, Material 3 UI.",
    ]
    for idx, line in enumerate(lines):
        page.insert_text((60, 72 + idx * 22), line, fontsize=11)
    y = 72 + len(lines) * 22
    page.insert_text((60, y + 12), "GitHub Portfolio (Click Here)", fontsize=11)
    page.insert_link({"kind": pymupdf.LINK_URI, "from": pymupdf.Rect(60, y, 240, y + 18), "uri": SAMPLE_GITHUB_URL})
    pdf_bytes = doc.tobytes()
    doc.close()
    return pdf_bytes


def _load_cv_input(args: argparse.Namespace) -> tuple[bytes, str, bytes | None]:
    if args.sample:
        cv_bytes, cv_mime = _generate_sample_cv_pdf(), "application/pdf"
    else:
        cv_path = Path(args.cv)
        if not cv_path.is_file():
            raise FileNotFoundError(f"CV file not found: {cv_path}")
        cv_mime = MIME_BY_SUFFIX.get(cv_path.suffix.lower(), "")
        if not cv_mime:
            raise ValueError(f"Unsupported CV extension: {cv_path.suffix}")
        cv_bytes = cv_path.read_bytes()
    li_bytes = Path(args.linkedin_pdf).read_bytes() if args.linkedin_pdf else None
    return cv_bytes, cv_mime, li_bytes


class _DryRunMatchRepository:
    """Calls live Supabase pgvector RPC while keeping match_results in memory."""

    def __init__(self, inner: Any) -> None:
        self._inner = inner

    async def search_job_postings(self, query_embedding: list[float], **kwargs: Any) -> list[dict[str, Any]]:
        return await self._inner.search_job_postings(query_embedding=query_embedding, **kwargs)

    async def save_match_results(
        self, job_id: str, profile_id: str, results: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        return [{"id": f"dryrun-{i}", "job_id": job_id, "profile_id": profile_id, **r} for i, r in enumerate(results, 1)]


async def _run_pipeline(args: argparse.Namespace) -> dict[str, Any]:
    load_dotenv(ROOT_DIR / ".env", override=False)
    if str(BACKEND_DIR) not in sys.path:
        sys.path.insert(0, str(BACKEND_DIR))

    from app.agents.matching_graph import build_matching_graph
    from app.core.config import settings
    from app.core.constants import EMBEDDING_TASK_QUERY, MIME_PDF, JobStatus
    from app.db.repositories.job_repository import JobRepository
    from app.db.repositories.match_repository import MatchRepository
    from app.db.repositories.profile_repository import ProfileRepository
    from app.services.cv_parser_service import extract_pdf_links_sync, parse_cv_document
    from app.services.embedding_service import (
        build_profile_embedding_text,
        generate_embedding,
    )
    from app.services.enrichment.enrichment_service import enrich_profile

    cv_bytes, cv_mime, li_bytes = _load_cv_input(args)
    pdf_links = await asyncio.to_thread(extract_pdf_links_sync, cv_bytes) if cv_mime == MIME_PDF else []
    logger.info(
        "E2E start | mode=%s | extract=%s | match=%s | fallback=%s | persist=%s",
        "sample" if args.sample else "cv_file",
        settings.GEMINI_MODEL_EXTRACTION,
        settings.GEMINI_MODEL_MATCHING,
        settings.GEMINI_MODEL_FALLBACK,
        args.persist,
    )
    t_all = time.perf_counter()

    t0 = time.perf_counter()
    extracted = await parse_cv_document(
        cv_bytes, cv_mime, linkedin_pdf_bytes=li_bytes, linkedin_content_type=MIME_PDF if li_bytes else None
    )
    t_extract = time.perf_counter() - t0
    cv_skill_keys = {s.name.strip().lower() for s in extracted.skills}

    t0 = time.perf_counter()
    enriched = await enrich_profile(extracted, has_linkedin_pdf=bool(li_bytes))
    t_enrich = time.perf_counter() - t0

    t0 = time.perf_counter()
    profile_payload: dict[str, Any] = {
        "full_name": "[REDACTED]",
        "headline": enriched.profile.headline,
        "years_of_experience": enriched.profile.years_of_experience,
        "skills": [s.model_dump() for s in enriched.profile.skills],
        "experiences": [e.model_dump() for e in enriched.profile.experiences],
        "external_links": enriched.profile.external_links.model_dump(),
        "enrichment_summary": enriched.enrichment_summary.model_dump(),
        "profile_summary": enriched.profile.profile_summary,
    }
    emb_vec = await generate_embedding(build_profile_embedding_text(profile_payload), task_type=EMBEDDING_TASK_QUERY)
    t_embed = time.perf_counter() - t0

    t0 = time.perf_counter()
    live_match_repo = MatchRepository()
    job_id, profile_id, user_id = str(uuid4()), str(uuid4()), args.user_id or "dryrun-user"

    if args.persist:
        job_repo, profile_repo = JobRepository(), ProfileRepository()
        persist_data = {**profile_payload, "full_name": enriched.profile.full_name, "embedding": emb_vec}
        profile_id = str((await profile_repo.create_profile(user_id, persist_data))["id"])
        job_id = str((await job_repo.create_job(user_id=user_id, profile_id=profile_id))["id"])
        await job_repo.update_job_status(job_id, JobStatus.MATCHING.value, 85, profile_id=profile_id)
        active_repo: Any = live_match_repo
    else:
        active_repo = _DryRunMatchRepository(live_match_repo)

    state = await build_matching_graph().ainvoke(
        {
            "job_id": job_id,
            "profile_id": profile_id,
            "user_id": user_id,
            "candidate_profile": profile_payload,
            "query_embedding": emb_vec,
            "filter_level": None,
            "warnings": [],
        },
        config={"configurable": {"match_repo": active_repo}},
    )
    if args.persist:
        await job_repo.update_job_status(job_id, JobStatus.COMPLETED.value, 100, profile_id=profile_id)
    t_match, t_total = time.perf_counter() - t0, time.perf_counter() - t_all

    meta_by_jp = {str(c.get("job_posting_id")): c for c in (state.get("retrieved_candidates") or [])}
    matches_report = [
        {
            "company_name": meta_by_jp.get(str(m.get("job_posting_id")), {}).get("company_name", "Unknown"),
            "job_title": meta_by_jp.get(str(m.get("job_posting_id")), {}).get("job_title", ""),
            "job_level": meta_by_jp.get(str(m.get("job_posting_id")), {}).get("job_level", ""),
            "company_location": meta_by_jp.get(str(m.get("job_posting_id")), {}).get("company_location", ""),
            "vector_similarity": meta_by_jp.get(str(m.get("job_posting_id")), {}).get("similarity"),
            **m,
        }
        for m in (state.get("saved_matches") or [])
    ]

    gh_verified = [s.name for s in enriched.profile.skills if "github" in s.verified_by]
    gh_new = [
        s.name for s in enriched.profile.skills if s.verified_by == ["github"] and s.name.strip().lower() not in cv_skill_keys
    ]
    web_res = state.get("web_research") or {}

    return {
        "timestamp_utc": datetime.now(tz=UTC).isoformat(),
        "mode": "sample" if args.sample else "cv_file",
        "persisted_to_db": bool(args.persist),
        "models": {
            "extraction": settings.GEMINI_MODEL_EXTRACTION,
            "matching": settings.GEMINI_MODEL_MATCHING,
            "fallback": settings.GEMINI_MODEL_FALLBACK,
            "embedding": settings.GEMINI_MODEL_EMBEDDING,
        },
        "latencies_seconds": {
            "extraction": round(t_extract, 2),
            "enrichment": round(t_enrich, 2),
            "embedding": round(t_embed, 2),
            "hybrid_matching": round(t_match, 2),
            "total": round(t_total, 2),
        },
        "profile_summary": {
            "headline": enriched.profile.headline,
            "years_of_experience": enriched.profile.years_of_experience,
            "pdf_annotation_links": pdf_links,
            "external_links": enriched.profile.external_links.model_dump(),
            "total_skills": len(enriched.profile.skills),
            "skills": [s.model_dump() for s in enriched.profile.skills],
            "github_verified_skills": gh_verified,
            "github_new_skills": gh_new,
        },
        "enrichment_summary": enriched.enrichment_summary.model_dump(),
        "matching_stats": {
            "retrieved_companies": len(state.get("retrieved_candidates") or []),
            "used_fallback_threshold": bool(state.get("used_fallback_threshold")),
            "tavily_companies_with_evidence": sum(1 for entries in web_res.values() if entries),
            "warnings": state.get("warnings") or [],
        },
        "matches": matches_report,
    }


def _log_report(report: dict[str, Any], output_path: Path) -> None:
    lat, prof = report["latencies_seconds"], report["profile_summary"]
    enr, stats = report["enrichment_summary"], report["matching_stats"]
    logger.info("=== 1. STAGE LATENCIES ===")
    logger.info(
        "Extraction=%.2fs | Enrichment=%.2fs | Embedding=%.2fs | Match=%.2fs | Total=%.2fs",
        lat["extraction"], lat["enrichment"], lat["embedding"], lat["hybrid_matching"], lat["total"],
    )
    logger.info("=== 2. PROFILE & GITHUB CROSS-VERIFICATION (PII-REDACTED) ===")
    logger.info("Headline: %s | Years: %.1f | PDF links: %s", prof["headline"], float(prof["years_of_experience"] or 0.0), prof["pdf_annotation_links"])
    logger.info(
        "Skills: total=%d | github_verified=%d (%s) | new_from_github=%d (%s)",
        prof["total_skills"], len(prof["github_verified_skills"]), ", ".join(prof["github_verified_skills"]),
        len(prof["github_new_skills"]), ", ".join(prof["github_new_skills"]),
    )
    logger.info("=== 3. ENRICHMENT & TAVILY STATUS ===")
    logger.info(
        "GitHub repos=%d (stars=%d) | Sources=%s | Tavily companies=%d | Fallback=%s",
        enr["github_public_repos"], enr["github_total_stars"], enr["source_statuses"],
        stats["tavily_companies_with_evidence"], stats["used_fallback_threshold"],
    )
    logger.info("=== 4. TOP 5 RECOMMENDED COMPANIES ===")
    for rank, m in enumerate(report["matches"][:5], start=1):
        logger.info(
            "#%d | %s - %s (%s) | Score=%d %s | Evidence=%d",
            rank, m["company_name"], m["job_title"], m["job_level"], m["match_score"],
            m["score_breakdown"], len(m.get("web_evidence") or []),
        )
        logger.info("    Matched: %s | Missing: %s", m["matched_skills"], m["missing_skills"])
        logger.info("    Reasoning: %s", m["reasoning"])
    logger.info("Full JSON report saved to: %s", output_path)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Live E2E test for JobRadar Backend")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--cv", type=str, help="Path to CV file (.pdf, .png, .jpg)")
    group.add_argument("--sample", action="store_true", help="Use built-in sample PDF CV")
    parser.add_argument("--linkedin-pdf", type=str, default=None, help="LinkedIn PDF path")
    parser.add_argument("--output", type=str, default=None, help="Output JSON path")
    parser.add_argument("--persist", action="store_true", help="Persist results to DB")
    parser.add_argument("--user-id", type=str, default=None, help="auth.users UUID")
    args = parser.parse_args()
    if args.persist:
        if not args.user_id:
            parser.error("--persist requires --user-id <uuid>")
        try:
            UUID(args.user_id)
        except ValueError:
            parser.error("--user-id must be a valid UUID")
    return args


def main() -> None:
    _configure_logging()
    args = _parse_args()
    report = asyncio.run(_run_pipeline(args))
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = Path(args.output) if args.output else ROOT_DIR / "scripts" / "output" / f"e2e_result_{ts}.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    _log_report(report, out_path)


if __name__ == "__main__":
    main()

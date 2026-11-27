"""Ablation Study runner executing Baseline (CV-Only) vs JobRadar Full (Hybrid+Enrichment)."""

from __future__ import annotations

import asyncio
import logging
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from dotenv import load_dotenv

from . import BACKEND_DIR, ROOT_DIR
from .dataset import BENCHMARK_DATASET, BenchmarkCvSpec, generate_cv_pdf
from .helpers import (
    DryRunMatchRepository,
    append_checkpoint,
    build_cv_record,
    join_candidate_metadata,
    load_completed_checkpoints,
    run_single_cv_offline,
)
from .metrics import aggregate_experiment_metrics

logger = logging.getLogger("benchmark.runner")

DEFAULT_OUTPUT_DIR = ROOT_DIR / "scripts" / "output" / "benchmark"
DEFAULT_DELAY_SECONDS = 4.0

__all__ = [
    "DEFAULT_DELAY_SECONDS",
    "DEFAULT_OUTPUT_DIR",
    "append_checkpoint",
    "run_benchmark",
]


def _profile_to_payload(profile: Any, enrichment_summary: dict[str, Any]) -> dict[str, Any]:
    return {
        "full_name": "[REDACTED]",
        "headline": profile.headline,
        "years_of_experience": profile.years_of_experience,
        "skills": [s.model_dump() for s in profile.skills],
        "experiences": [e.model_dump() for e in profile.experiences],
        "external_links": profile.external_links.model_dump(),
        "enrichment_summary": enrichment_summary,
        "profile_summary": profile.profile_summary,
    }


async def _run_single_cv_live(
    spec: BenchmarkCvSpec,
    *,
    extraction_model: str | None = None,
    matching_model: str | None = None,
) -> dict[str, Any]:
    load_dotenv(ROOT_DIR / ".env", override=False)
    from app.agents.matching_graph import build_matching_graph
    from app.core.config import settings
    from app.core.constants import EMBEDDING_TASK_QUERY, MIME_PDF
    from app.db.repositories.match_repository import MatchRepository
    from app.schemas.profile import EnrichmentSummary, ExternalLinks, SkillItem
    from app.services.cv_parser_service import extract_pdf_links_sync, parse_cv_document
    from app.services.embedding_service import (
        build_profile_embedding_text,
        generate_embedding,
    )
    from app.services.enrichment.enrichment_service import (
        _canonicalize_skill_key,
        enrich_profile,
    )

    orig_extract = settings.GEMINI_MODEL_EXTRACTION
    orig_match = settings.GEMINI_MODEL_MATCHING
    orig_tavily = settings.TAVILY_API_KEY
    if extraction_model:
        settings.GEMINI_MODEL_EXTRACTION = extraction_model
    if matching_model:
        settings.GEMINI_MODEL_MATCHING = matching_model

    try:
        cv_bytes = generate_cv_pdf(spec)
        pdf_links = await asyncio.to_thread(extract_pdf_links_sync, cv_bytes)

        t0 = time.perf_counter()
        extracted = await parse_cv_document(cv_bytes, MIME_PDF)
        extraction_s = time.perf_counter() - t0
        cv_skill_keys = {_canonicalize_skill_key(s.name) for s in extracted.skills}

        dry_repo = DryRunMatchRepository(MatchRepository())
        graph = build_matching_graph()

        # Branch 1: Baseline (CV-Only — no enrichment, no GitHub verification, no Tavily).
        base_skills = [
            SkillItem(name=s.name, category=s.category, level=s.level, verified_by=["cv"])
            for s in extracted.skills
        ]
        base_profile = extracted.model_copy(
            update={"skills": base_skills, "external_links": ExternalLinks()}
        )
        base_payload = _profile_to_payload(base_profile, EnrichmentSummary().model_dump())

        t0 = time.perf_counter()
        base_emb = await generate_embedding(
            build_profile_embedding_text(base_payload), task_type=EMBEDDING_TASK_QUERY
        )
        baseline_embed_s = time.perf_counter() - t0

        settings.TAVILY_API_KEY = ""
        try:
            t0 = time.perf_counter()
            base_state = await graph.ainvoke(
                {
                    "job_id": str(uuid4()),
                    "profile_id": str(uuid4()),
                    "user_id": f"bench-base-{spec.cv_id}",
                    "candidate_profile": base_payload,
                    "query_embedding": base_emb,
                    "filter_level": None,
                    "warnings": [],
                },
                config={"configurable": {"match_repo": dry_repo}},
            )
            baseline_match_s = time.perf_counter() - t0
        finally:
            settings.TAVILY_API_KEY = orig_tavily

        # Branch 2: JobRadar Full (GitHub/Jina Enrichment + Skill Cross-Verify + Tavily Hybrid).
        t0 = time.perf_counter()
        enriched = await enrich_profile(extracted, has_linkedin_pdf=False)
        enrichment_s = time.perf_counter() - t0

        full_payload = _profile_to_payload(
            enriched.profile, enriched.enrichment_summary.model_dump()
        )
        t0 = time.perf_counter()
        full_emb = await generate_embedding(
            build_profile_embedding_text(full_payload), task_type=EMBEDDING_TASK_QUERY
        )
        full_embed_s = time.perf_counter() - t0

        t0 = time.perf_counter()
        full_state = await graph.ainvoke(
            {
                "job_id": str(uuid4()),
                "profile_id": str(uuid4()),
                "user_id": f"bench-full-{spec.cv_id}",
                "candidate_profile": full_payload,
                "query_embedding": full_emb,
                "filter_level": None,
                "warnings": [],
            },
            config={"configurable": {"match_repo": dry_repo}},
        )
        full_match_s = time.perf_counter() - t0

        gh_verified = sum(1 for s in enriched.profile.skills if "github" in s.verified_by)
        gh_new = sum(
            1
            for s in enriched.profile.skills
            if s.verified_by == ["github"]
            and _canonicalize_skill_key(s.name) not in cv_skill_keys
        )
        return build_cv_record(
            spec,
            mode="live",
            baseline_matches=join_candidate_metadata(base_state),
            full_matches=join_candidate_metadata(full_state),
            cv_total_skills=len(extracted.skills),
            full_total_skills=len(enriched.profile.skills),
            github_verified_skills=gh_verified,
            new_github_skills=gh_new,
            latencies={
                "extraction_s": extraction_s,
                "enrichment_s": enrichment_s,
                "baseline_embed_s": baseline_embed_s,
                "baseline_match_s": baseline_match_s,
                "full_embed_s": full_embed_s,
                "full_match_s": full_match_s,
            },
            pdf_links=pdf_links,
        )
    finally:
        settings.GEMINI_MODEL_EXTRACTION = orig_extract
        settings.GEMINI_MODEL_MATCHING = orig_match
        settings.TAVILY_API_KEY = orig_tavily


async def run_benchmark(
    *,
    mode: str = "offline",
    limit: int | None = None,
    delay_seconds: float = DEFAULT_DELAY_SECONDS,
    resume: bool = False,
    extraction_model: str | None = None,
    matching_model: str | None = None,
    output_dir: Path = DEFAULT_OUTPUT_DIR,
    checkpoint_path: Path | None = None,
) -> dict[str, Any]:
    """Execute the Ablation Study across the dataset with pacing and JSONL checkpointing."""
    specs = BENCHMARK_DATASET[:limit] if limit is not None else BENCHMARK_DATASET
    active_ckpt = checkpoint_path
    if active_ckpt is None and mode == "live":
        active_ckpt = output_dir / "checkpoint.jsonl"

    completed_map: dict[str, dict[str, Any]] = {}
    if active_ckpt is not None:
        if resume:
            completed_map = load_completed_checkpoints(active_ckpt, mode)
            if completed_map:
                logger.info(
                    "Resuming from checkpoint: %d CV(s) already completed.",
                    len(completed_map),
                )
        elif active_ckpt.exists():
            active_ckpt.unlink()

    records: list[dict[str, Any]] = []
    for idx, spec in enumerate(specs):
        if spec.cv_id in completed_map:
            logger.info("[%d/%d] Skipping completed CV '%s'.", idx + 1, len(specs), spec.cv_id)
            records.append(completed_map[spec.cv_id])
            continue

        logger.info(
            "[%d/%d] Running %s benchmark for '%s'...",
            idx + 1,
            len(specs),
            mode,
            spec.cv_id,
        )
        try:
            if mode == "live":
                record = await _run_single_cv_live(
                    spec, extraction_model=extraction_model, matching_model=matching_model
                )
            else:
                record = run_single_cv_offline(spec, idx)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 — isolate batch CV failure and continue
            logger.error("Benchmark failed for '%s': %s", spec.cv_id, exc)
            record = {
                "cv_id": spec.cv_id,
                "domain": spec.domain,
                "expected_level": spec.expected_level,
                "mode": mode,
                "status": "failed",
                "error": str(exc)[:300],
            }

        records.append(record)
        if active_ckpt is not None:
            append_checkpoint(active_ckpt, record)

        if mode == "live" and delay_seconds > 0 and idx < len(specs) - 1:
            logger.info("Pacing %.1fs before next CV to protect free-tier RPM...", delay_seconds)
            await asyncio.sleep(delay_seconds)

    if mode == "live":
        load_dotenv(ROOT_DIR / ".env", override=False)
        from app.core.config import settings

        ext_name = extraction_model or settings.GEMINI_MODEL_EXTRACTION
        match_name = matching_model or settings.GEMINI_MODEL_MATCHING
    else:
        ext_name, match_name = "offline-simulated", "offline-simulated"

    return {
        "timestamp_utc": datetime.now(tz=UTC).isoformat(),
        "mode": mode,
        "backend_dir": str(BACKEND_DIR),
        "extraction_model": ext_name,
        "matching_model": match_name,
        "summary": aggregate_experiment_metrics(records),
        "records": records,
    }

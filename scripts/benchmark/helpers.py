"""Shared dry-run repository, checkpointing, record builder, and offline simulator."""

from __future__ import annotations

import json
import logging
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from . import ROOT_DIR
from .dataset import BenchmarkCvSpec, generate_cv_pdf
from .metrics import (
    mrr_at_k,
    precision_at_k,
    rank_shift_metrics,
    web_evidence_coverage_at_k,
)

logger = logging.getLogger("benchmark.helpers")


class DryRunMatchRepository:
    """Executes live Supabase pgvector RPC while keeping match_results purely in memory."""

    def __init__(self, inner: Any) -> None:
        self._inner = inner

    async def search_job_postings(
        self, query_embedding: list[float], **kwargs: Any
    ) -> list[dict[str, Any]]:
        return await self._inner.search_job_postings(query_embedding=query_embedding, **kwargs)

    async def save_match_results(
        self, job_id: str, profile_id: str, results: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        return [
            {"id": f"dryrun-{idx}", "job_id": job_id, "profile_id": profile_id, **item}
            for idx, item in enumerate(results, start=1)
        ]


def load_completed_checkpoints(checkpoint_path: Path, mode: str) -> dict[str, dict[str, Any]]:
    """Load completed per-CV records from a JSONL checkpoint file matching the active mode."""
    if not checkpoint_path.is_file():
        return {}
    completed: dict[str, dict[str, Any]] = {}
    for line in checkpoint_path.read_text(encoding="utf-8").splitlines():
        raw = line.strip()
        if not raw:
            continue
        try:
            record = json.loads(raw)
        except json.JSONDecodeError:
            logger.warning("Skipping malformed checkpoint line in %s.", checkpoint_path)
            continue
        if (
            isinstance(record, dict)
            and record.get("status") == "completed"
            and record.get("mode") == mode
            and record.get("cv_id")
        ):
            completed[str(record["cv_id"])] = record
    return completed


def append_checkpoint(checkpoint_path: Path, record: Mapping[str, Any]) -> None:
    """Append a single CV benchmark record as one JSON line."""
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    with checkpoint_path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def join_candidate_metadata(state: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Attach retrieved company/job metadata onto saved match records."""
    meta_by_jp = {
        str(c.get("job_posting_id")): c for c in (state.get("retrieved_candidates") or [])
    }
    joined: list[dict[str, Any]] = []
    for match in state.get("saved_matches") or []:
        meta = meta_by_jp.get(str(match.get("job_posting_id")), {})
        joined.append(
            {
                "company_name": meta.get("company_name", "Unknown"),
                "job_title": meta.get("job_title", ""),
                "job_level": meta.get("job_level", ""),
                **match,
            }
        )
    return joined


def build_cv_record(
    spec: BenchmarkCvSpec,
    *,
    mode: str,
    baseline_matches: Sequence[Mapping[str, Any]],
    full_matches: Sequence[Mapping[str, Any]],
    cv_total_skills: int,
    full_total_skills: int,
    github_verified_skills: int,
    new_github_skills: int,
    latencies: Mapping[str, float],
    pdf_links: Sequence[str],
) -> dict[str, Any]:
    """Assemble standardized per-CV comparison metrics for Baseline vs Full."""
    shift = rank_shift_metrics(baseline_matches, full_matches, k=5)
    base_top1 = baseline_matches[0] if baseline_matches else {}
    full_top1 = full_matches[0] if full_matches else {}
    return {
        "cv_id": spec.cv_id,
        "domain": spec.domain,
        "expected_level": spec.expected_level,
        "mode": mode,
        "status": "completed",
        "error": "",
        "pdf_links_count": len(pdf_links),
        "cv_total_skills": cv_total_skills,
        "full_total_skills": full_total_skills,
        "github_verified_skills": github_verified_skills,
        "new_github_skills": new_github_skills,
        "extraction_s": round(latencies["extraction_s"], 3),
        "enrichment_s": round(latencies["enrichment_s"], 3),
        "baseline_embed_s": round(latencies["baseline_embed_s"], 3),
        "baseline_match_s": round(latencies["baseline_match_s"], 3),
        "baseline_total_s": round(
            latencies["extraction_s"]
            + latencies["baseline_embed_s"]
            + latencies["baseline_match_s"],
            3,
        ),
        "full_embed_s": round(latencies["full_embed_s"], 3),
        "full_match_s": round(latencies["full_match_s"], 3),
        "full_total_s": round(
            latencies["extraction_s"]
            + latencies["enrichment_s"]
            + latencies["full_embed_s"]
            + latencies["full_match_s"],
            3,
        ),
        "baseline_precision_at_5": precision_at_k(
            baseline_matches, spec.expected_role_keywords, spec.expected_level, k=5
        ),
        "full_precision_at_5": precision_at_k(
            full_matches, spec.expected_role_keywords, spec.expected_level, k=5
        ),
        "baseline_mrr_at_5": mrr_at_k(
            baseline_matches, spec.expected_role_keywords, spec.expected_level, k=5
        ),
        "full_mrr_at_5": mrr_at_k(
            full_matches, spec.expected_role_keywords, spec.expected_level, k=5
        ),
        "top5_jaccard": shift["top5_jaccard"],
        "new_companies_in_top5": shift["new_companies_entered_top5"],
        "avg_rank_delta": shift["avg_rank_delta"],
        "baseline_web_evidence_ratio": web_evidence_coverage_at_k(baseline_matches, k=5),
        "full_web_evidence_ratio": web_evidence_coverage_at_k(full_matches, k=5),
        "baseline_top1_company": str(base_top1.get("company_name") or ""),
        "baseline_top1_score": int(base_top1.get("match_score") or 0),
        "full_top1_company": str(full_top1.get("company_name") or ""),
        "full_top1_score": int(full_top1.get("match_score") or 0),
    }


def run_single_cv_offline(spec: BenchmarkCvSpec, index: int) -> dict[str, Any]:
    """Deterministic zero-API simulation exercising real PDF generation and metric formulas."""
    from app.services.cv_parser_service import extract_pdf_links_sync

    cv_bytes = generate_cv_pdf(spec)
    pdf_links = extract_pdf_links_sync(cv_bytes)

    companies = json.loads((ROOT_DIR / "scripts" / "data" / "companies.json").read_text("utf-8"))
    comp_offset = (index * 3) % len(companies)
    picked = [companies[(comp_offset + i) % len(companies)]["name"] for i in range(6)]
    kw = spec.expected_role_keywords[0].title()

    baseline_matches = [
        {
            "company_id": f"c-{i}",
            "company_name": picked[i],
            "job_title": f"{kw} Engineer" if i in {0, 2} else "Software Engineer",
            "job_level": spec.expected_level if i < 2 else "senior",
            "matched_skills": list(spec.declared_skills[:2]),
            "match_score": 76 - i * 3,
            "web_evidence": [],
        }
        for i in range(5)
    ]
    full_matches = [
        {
            "company_id": f"c-{idx}",
            "company_name": picked[idx],
            "job_title": f"{kw} Developer" if i < 4 else "Platform Engineer",
            "job_level": spec.expected_level if i < 4 else "senior",
            "matched_skills": list(spec.declared_skills[:3]),
            "match_score": 88 - i * 3,
            "web_evidence": [
                {"title": picked[idx], "url": "https://example.com", "snippet": "ok"}
            ],
        }
        for i, idx in enumerate((0, 5, 2, 1, 4))
    ]
    cv_count = len(spec.declared_skills)
    new_gh = max(1, spec.expected_min_github_skills)
    return build_cv_record(
        spec,
        mode="offline",
        baseline_matches=baseline_matches,
        full_matches=full_matches,
        cv_total_skills=cv_count,
        full_total_skills=cv_count + new_gh,
        github_verified_skills=min(cv_count, 2) + new_gh,
        new_github_skills=new_gh,
        latencies={
            "extraction_s": 2.4 + (index % 3) * 0.2,
            "enrichment_s": 1.6 + (index % 2) * 0.3,
            "baseline_embed_s": 0.35,
            "baseline_match_s": 3.1,
            "full_embed_s": 0.36,
            "full_match_s": 5.2,
        },
        pdf_links=pdf_links,
    )

"""Offline unit tests for benchmark dataset, metrics, checkpoint/resume, and reporting."""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from .dataset import BENCHMARK_DATASET, generate_cv_pdf
from .metrics import (
    aggregate_experiment_metrics,
    has_role_keyword_match,
    is_level_compatible,
    mrr_at_k,
    precision_at_k,
    rank_shift_metrics,
    web_evidence_coverage_at_k,
)
from .report_generator import generate_benchmark_reports
from .runner import append_checkpoint, run_benchmark


def test_dataset_covers_12_diverse_specs_and_pdf_hidden_links() -> None:
    from app.services.cv_parser_service import extract_pdf_links_sync

    assert len(BENCHMARK_DATASET) == 12
    levels = {spec.expected_level for spec in BENCHMARK_DATASET}
    assert levels == {"intern", "fresher", "junior", "mid"}

    first_three = BENCHMARK_DATASET[:3]
    assert len({s.domain for s in first_three}) == 3
    assert len({s.expected_level for s in first_three}) == 3

    for spec in BENCHMARK_DATASET:
        pdf_bytes = generate_cv_pdf(spec)
        assert pdf_bytes.startswith(b"%PDF-")
        links = extract_pdf_links_sync(pdf_bytes)
        assert spec.github_url in links


def test_precision_and_relevance_rules() -> None:
    assert is_level_compatible("intern", "fresher")
    assert is_level_compatible("fresher", "junior")
    assert not is_level_compatible("intern", "mid")
    assert not is_level_compatible(None, "fresher")

    # Word boundary prevents "ai" from false-matching "Tailwind CSS".
    tailwind_match = {"job_title": "Frontend Developer", "matched_skills": ["Tailwind CSS"]}
    assert not has_role_keyword_match(tailwind_match, ("ai", "pytorch"))
    ai_match = {"job_title": "AI / Generative AI Engineer", "matched_skills": ["PyTorch"]}
    assert has_role_keyword_match(ai_match, ("ai", "pytorch"))

    matches = [
        {"job_title": "Android Developer", "job_level": "fresher", "matched_skills": ["Kotlin"]},
        {"job_title": "Android Engineer", "job_level": "junior", "matched_skills": ["Kotlin"]},
        {"job_title": "Backend Java", "job_level": "fresher", "matched_skills": ["Java"]},
        {"job_title": "Senior Android", "job_level": "senior", "matched_skills": ["Kotlin"]},
        {"job_title": "Mobile Android", "job_level": "intern", "matched_skills": ["Android"]},
    ]
    # Relevant items: #1 (fresher), #2 (junior, +1), #5 (intern, -1) -> 3 / 5 = 0.6
    assert precision_at_k(matches, ("android", "kotlin"), "fresher", k=5) == 0.6


def test_mrr_at_k_and_rank_shift_metrics() -> None:
    misses_then_hit = [
        {"job_title": "DevOps Engineer", "job_level": "mid", "matched_skills": ["Docker"]},
        {"job_title": "QA Engineer", "job_level": "junior", "matched_skills": ["Selenium"]},
        {"job_title": "Backend Python", "job_level": "junior", "matched_skills": ["Python"]},
    ]
    assert mrr_at_k(misses_then_hit, ("python", "backend"), "junior", k=5) == 0.3333
    assert mrr_at_k([], ("python",), "junior", k=5) == 0.0

    baseline = [{"company_id": f"c{i}"} for i in range(1, 6)]
    full = [
        {"company_id": "c1"},
        {"company_id": "c6"},
        {"company_id": "c3"},
        {"company_id": "c2"},
        {"company_id": "c7"},
    ]
    shift = rank_shift_metrics(baseline, full, k=5)
    # Set intersection={c1,c2,c3}=3, union={c1..c7}=7 -> Jaccard 3/7 = 0.4286
    assert shift["top5_jaccard"] == 0.4286
    assert shift["new_companies_entered_top5"] == 2
    # Deltas: c1(1->1=0), c6(6->2=4), c3(3->3=0), c2(2->4=2), c7(6->5=1) -> 7/5 = 1.4
    assert shift["avg_rank_delta"] == 1.4

    cov = web_evidence_coverage_at_k(
        [{"web_evidence": [{"title": "x"}]}, {"web_evidence": []}, {"web_evidence": [{"t": "y"}]}],
        k=5,
    )
    assert cov == 0.6667


def test_aggregate_experiment_metrics_with_failed_record_resilience() -> None:
    records = [
        {
            "cv_id": "cv_1",
            "status": "completed",
            "baseline_precision_at_5": 0.4,
            "full_precision_at_5": 0.8,
            "baseline_mrr_at_5": 0.5,
            "full_mrr_at_5": 1.0,
            "baseline_top1_score": 70,
            "full_top1_score": 88,
            "baseline_web_evidence_ratio": 0.0,
            "full_web_evidence_ratio": 1.0,
            "cv_total_skills": 4,
            "full_total_skills": 6,
            "github_verified_skills": 4,
            "new_github_skills": 2,
            "top5_jaccard": 0.6,
            "new_companies_in_top5": 2,
            "avg_rank_delta": 1.2,
            "extraction_s": 2.0,
            "enrichment_s": 1.5,
            "baseline_embed_s": 0.3,
            "baseline_match_s": 3.0,
            "baseline_total_s": 5.3,
            "full_embed_s": 0.4,
            "full_match_s": 5.0,
            "full_total_s": 8.9,
        },
        {"cv_id": "cv_2", "status": "failed", "error": "rate_limit"},
    ]
    agg = aggregate_experiment_metrics(records)
    assert agg["total_cvs"] == 2
    assert agg["completed_cvs"] == 1
    assert agg["failed_cvs"] == 1
    assert agg["precision_at_5_delta"] == 0.4
    assert agg["precision_at_5_rel_pct"] == 100.0
    assert agg["github_verified_ratio"] == round(4 / 6, 4)
    assert agg["full_web_evidence_top5_ratio"] == 1.0


@pytest.mark.asyncio
async def test_offline_runner_checkpoint_resume_and_reports(tmp_path: Path) -> None:
    ckpt_file = tmp_path / "checkpoint.jsonl"
    first_run = await run_benchmark(
        mode="offline",
        limit=2,
        output_dir=tmp_path,
        checkpoint_path=ckpt_file,
    )
    assert first_run["summary"]["completed_cvs"] == 2

    # Inject a failed record for the 3rd CV to ensure --resume retries failed entries.
    third_id = BENCHMARK_DATASET[2].cv_id
    append_checkpoint(
        ckpt_file,
        {"cv_id": third_id, "mode": "offline", "status": "failed", "error": "transient"},
    )

    resumed_run = await run_benchmark(
        mode="offline",
        limit=4,
        resume=True,
        output_dir=tmp_path,
        checkpoint_path=ckpt_file,
    )
    assert resumed_run["summary"]["total_cvs"] == 4
    assert resumed_run["summary"]["completed_cvs"] == 4

    # Checkpoint lines should have 2 initial + 1 injected failed + 2 resumed (cv_03 & cv_04) = 5.
    raw_lines = [json.loads(line) for line in ckpt_file.read_text(encoding="utf-8").splitlines()]
    assert len(raw_lines) == 5

    csv_path, md_path = generate_benchmark_reports(resumed_run, tmp_path)
    assert csv_path.is_file() and md_path.is_file()
    with csv_path.open("r", encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle))
    assert len(rows) == 4
    md_text = md_path.read_text(encoding="utf-8")
    assert "Baseline (CV-Only)" in md_text
    assert "JobRadar Full" in md_text

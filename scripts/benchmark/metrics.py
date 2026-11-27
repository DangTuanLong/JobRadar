"""Pure quantitative metrics for JobRadar Baseline vs Full Ablation Study.

Ground-Truth Relevance Rule:
A match item in Top-K is counted as relevant if and only if:
1. Its `job_title` or `matched_skills` contains at least one keyword from
   `expected_role_keywords` (case-insensitive word-boundary match), AND
2. Its `job_level` is compatible with `expected_level` within +/- 1 adjacent
   career tier along (intern, fresher, junior, mid, senior), e.g. intern<->fresher
   or fresher<->junior.
"""

from __future__ import annotations

import re
import statistics
from collections.abc import Mapping, Sequence
from typing import Any

LEVEL_ORDER: tuple[str, ...] = ("intern", "fresher", "junior", "mid", "senior")
_LEVEL_INDEX: dict[str, int] = {lvl: idx for idx, lvl in enumerate(LEVEL_ORDER)}


def is_level_compatible(
    job_level: str | None,
    expected_level: str,
    *,
    max_distance: int = 1,
) -> bool:
    """Return True when job_level is within +/- max_distance tiers of expected_level."""
    norm_job = (job_level or "").strip().lower()
    norm_exp = expected_level.strip().lower()
    if norm_job not in _LEVEL_INDEX or norm_exp not in _LEVEL_INDEX:
        return False
    return abs(_LEVEL_INDEX[norm_job] - _LEVEL_INDEX[norm_exp]) <= max_distance


def has_role_keyword_match(
    match: Mapping[str, Any],
    expected_role_keywords: Sequence[str],
) -> bool:
    """Check if job_title or matched_skills match any expected role keyword cleanly."""
    if not expected_role_keywords:
        return False
    haystack_parts = [str(match.get("job_title") or "")]
    skills = match.get("matched_skills") or []
    if isinstance(skills, Sequence) and not isinstance(skills, (str, bytes)):
        haystack_parts.extend(str(s) for s in skills)
    haystack = " | ".join(haystack_parts).lower()

    for keyword in expected_role_keywords:
        clean_kw = keyword.strip().lower()
        if not clean_kw:
            continue
        pattern = rf"(?<![a-z0-9]){re.escape(clean_kw)}(?![a-z0-9])"
        if re.search(pattern, haystack):
            return True
    return False


def is_match_relevant(
    match: Mapping[str, Any],
    expected_role_keywords: Sequence[str],
    expected_level: str,
) -> bool:
    """Evaluate ground-truth relevance using both specialization keyword and career tier."""
    return has_role_keyword_match(match, expected_role_keywords) and is_level_compatible(
        match.get("job_level"),
        expected_level,
    )


def precision_at_k(
    matches: Sequence[Mapping[str, Any]],
    expected_role_keywords: Sequence[str],
    expected_level: str,
    k: int = 5,
) -> float:
    """Compute Precision@K over the first k ranked matches."""
    if k <= 0 or not matches:
        return 0.0
    top_k = list(matches)[:k]
    relevant = sum(
        1
        for item in top_k
        if is_match_relevant(item, expected_role_keywords, expected_level)
    )
    return round(relevant / k, 4)


def mrr_at_k(
    matches: Sequence[Mapping[str, Any]],
    expected_role_keywords: Sequence[str],
    expected_level: str,
    k: int = 5,
) -> float:
    """Compute Mean Reciprocal Rank (1/rank of first relevant match) within Top-K."""
    if k <= 0 or not matches:
        return 0.0
    for rank, item in enumerate(list(matches)[:k], start=1):
        if is_match_relevant(item, expected_role_keywords, expected_level):
            return round(1.0 / rank, 4)
    return 0.0


def _company_key(match: Mapping[str, Any]) -> str:
    company_id = str(match.get("company_id") or "").strip().lower()
    if company_id:
        return company_id
    return str(match.get("company_name") or "").strip().lower()


def rank_shift_metrics(
    baseline_matches: Sequence[Mapping[str, Any]],
    full_matches: Sequence[Mapping[str, Any]],
    k: int = 5,
) -> dict[str, float | int]:
    """Measure Top-K Jaccard similarity, new Top-K entrants, and average rank shift."""
    if k <= 0:
        return {"top5_jaccard": 1.0, "new_companies_entered_top5": 0, "avg_rank_delta": 0.0}

    base_all_keys = [_company_key(m) for m in baseline_matches if _company_key(m)]
    full_all_keys = [_company_key(m) for m in full_matches if _company_key(m)]
    base_top = base_all_keys[:k]
    full_top = full_all_keys[:k]

    base_set, full_set = set(base_top), set(full_top)
    union = base_set | full_set
    jaccard = len(base_set & full_set) / len(union) if union else 1.0
    new_entrants = len(full_set - base_set)

    base_rank_map: dict[str, int] = {}
    for rank, key in enumerate(base_all_keys, start=1):
        base_rank_map.setdefault(key, rank)

    absent_rank = max(len(base_all_keys), k) + 1
    deltas = [
        abs(base_rank_map.get(key, absent_rank) - full_rank)
        for full_rank, key in enumerate(full_top, start=1)
    ]
    avg_delta = statistics.mean(deltas) if deltas else 0.0

    return {
        "top5_jaccard": round(jaccard, 4),
        "new_companies_entered_top5": new_entrants,
        "avg_rank_delta": round(avg_delta, 4),
    }


def web_evidence_coverage_at_k(
    matches: Sequence[Mapping[str, Any]],
    k: int = 5,
) -> float:
    """Compute the ratio of Top-K matches backed by at least one web_evidence snippet."""
    if k <= 0 or not matches:
        return 0.0
    top_k = list(matches)[:k]
    covered = sum(1 for item in top_k if bool(item.get("web_evidence")))
    return round(covered / len(top_k), 4)


def _mean(values: Sequence[float]) -> float:
    return round(float(statistics.mean(values)), 4) if values else 0.0


def _median(values: Sequence[float]) -> float:
    return round(float(statistics.median(values)), 4) if values else 0.0


def _rel_pct(baseline: float, full: float) -> float:
    if baseline == 0.0:
        return 0.0 if full == 0.0 else 100.0
    return round(((full - baseline) / baseline) * 100.0, 2)


def aggregate_experiment_metrics(records: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """Aggregate per-CV records into mean/median Ablation Study summary metrics."""
    completed = [r for r in records if r.get("status") == "completed"]
    failed_count = len(records) - len(completed)

    def _vals(key: str) -> list[float]:
        return [float(r.get(key) or 0.0) for r in completed]

    base_p5, full_p5 = _mean(_vals("baseline_precision_at_5")), _mean(_vals("full_precision_at_5"))
    base_mrr, full_mrr = _mean(_vals("baseline_mrr_at_5")), _mean(_vals("full_mrr_at_5"))
    base_top1 = _mean(_vals("baseline_top1_score"))
    full_top1 = _mean(_vals("full_top1_score"))

    total_full_skills = sum(int(r.get("full_total_skills") or 0) for r in completed)
    total_gh_verified = sum(int(r.get("github_verified_skills") or 0) for r in completed)
    gh_ratio = round(total_gh_verified / total_full_skills, 4) if total_full_skills > 0 else 0.0

    base_total_s = _vals("baseline_total_s")
    full_total_s = _vals("full_total_s")

    return {
        "total_cvs": len(records),
        "completed_cvs": len(completed),
        "failed_cvs": failed_count,
        "baseline_precision_at_5": base_p5,
        "full_precision_at_5": full_p5,
        "precision_at_5_delta": round(full_p5 - base_p5, 4),
        "precision_at_5_rel_pct": _rel_pct(base_p5, full_p5),
        "baseline_mrr_at_5": base_mrr,
        "full_mrr_at_5": full_mrr,
        "mrr_at_5_delta": round(full_mrr - base_mrr, 4),
        "mrr_at_5_rel_pct": _rel_pct(base_mrr, full_mrr),
        "baseline_top1_score_mean": base_top1,
        "full_top1_score_mean": full_top1,
        "top1_score_delta": round(full_top1 - base_top1, 4),
        "top1_score_rel_pct": _rel_pct(base_top1, full_top1),
        "baseline_web_evidence_top5_ratio": _mean(_vals("baseline_web_evidence_ratio")),
        "full_web_evidence_top5_ratio": _mean(_vals("full_web_evidence_ratio")),
        "avg_cv_skills": _mean(_vals("cv_total_skills")),
        "avg_full_skills": _mean(_vals("full_total_skills")),
        "avg_github_verified_skills": _mean(_vals("github_verified_skills")),
        "github_verified_ratio": gh_ratio,
        "avg_new_github_skills": _mean(_vals("new_github_skills")),
        "mean_top5_jaccard": _mean(_vals("top5_jaccard")),
        "mean_new_companies_in_top5": _mean(_vals("new_companies_in_top5")),
        "mean_rank_delta": _mean(_vals("avg_rank_delta")),
        "latency_mean_s": {
            "extraction": _mean(_vals("extraction_s")),
            "enrichment": _mean(_vals("enrichment_s")),
            "baseline_embed": _mean(_vals("baseline_embed_s")),
            "baseline_match": _mean(_vals("baseline_match_s")),
            "baseline_total": _mean(base_total_s),
            "full_embed": _mean(_vals("full_embed_s")),
            "full_match": _mean(_vals("full_match_s")),
            "full_total": _mean(full_total_s),
        },
        "latency_median_s": {
            "extraction": _median(_vals("extraction_s")),
            "enrichment": _median(_vals("enrichment_s")),
            "baseline_embed": _median(_vals("baseline_embed_s")),
            "baseline_match": _median(_vals("baseline_match_s")),
            "baseline_total": _median(base_total_s),
            "full_embed": _median(_vals("full_embed_s")),
            "full_match": _median(_vals("full_match_s")),
            "full_total": _median(full_total_s),
        },
    }

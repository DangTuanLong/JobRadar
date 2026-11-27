"""CSV and academic Vietnamese Markdown report generator for Ablation Study."""

from __future__ import annotations

import csv
from collections.abc import Mapping
from datetime import datetime
from pathlib import Path
from typing import Any

CSV_FIELDNAMES: tuple[str, ...] = (
    "cv_id",
    "domain",
    "expected_level",
    "mode",
    "status",
    "pdf_links_count",
    "cv_total_skills",
    "full_total_skills",
    "github_verified_skills",
    "new_github_skills",
    "extraction_s",
    "enrichment_s",
    "baseline_embed_s",
    "baseline_match_s",
    "baseline_total_s",
    "full_embed_s",
    "full_match_s",
    "full_total_s",
    "baseline_precision_at_5",
    "full_precision_at_5",
    "baseline_mrr_at_5",
    "full_mrr_at_5",
    "top5_jaccard",
    "new_companies_in_top5",
    "avg_rank_delta",
    "baseline_web_evidence_ratio",
    "full_web_evidence_ratio",
    "baseline_top1_company",
    "baseline_top1_score",
    "full_top1_company",
    "full_top1_score",
    "error",
)


def _fmt_pct_change(rel_pct: float) -> str:
    sign = "+" if rel_pct > 0 else ""
    return f"{sign}{rel_pct:.2f}%"


def _fmt_delta(delta: float, unit: str = "") -> str:
    sign = "+" if delta > 0 else ""
    return f"{sign}{delta:.4f}{unit}" if abs(delta) < 10 else f"{sign}{delta:.2f}{unit}"


def _build_markdown_report(run_result: Mapping[str, Any]) -> str:
    s: Mapping[str, Any] = run_result["summary"]
    lat_mean: Mapping[str, Any] = s["latency_mean_s"]
    lat_med: Mapping[str, Any] = s["latency_median_s"]
    records = run_result["records"]

    base_total = float(lat_mean["baseline_total"])
    full_total = float(lat_mean["full_total"])
    total_lat_delta = full_total - base_total
    total_lat_pct = ((total_lat_delta / base_total) * 100.0) if base_total > 0 else 0.0

    lines: list[str] = [
        "# Báo cáo Thực nghiệm Định lượng (Ablation Study) — JobRadar",
        "",
        "## 1. Thiết lập & Điều kiện Thí nghiệm",
        f"- **Chế độ chạy (`mode`):** `{run_result['mode']}`",
        f"- **Model Trích xuất CV:** `{run_result['extraction_model']}`",
        f"- **Model Đánh giá & Chấm điểm:** `{run_result['matching_model']}`",
        (
            f"- **Số lượng hồ sơ CV:** {s['completed_cvs']}/{s['total_cvs']} hoàn tất "
            f"(lỗi: {s['failed_cvs']})"
        ),
        "- **Kho tri thức tuyển dụng:** 32 công ty / 128 JD chuẩn hóa trên Supabase `pgvector`.",
        (
            "- **Thiết lập Ablation công bằng:** Stage 1 (CV Extraction) chạy đúng 1 lần trên mỗi "
            "CV, sau đó dùng chung `ExtractedProfile` cho cả **Baseline (CV-Only)** (tắt "
            "GitHub/Jina Enrichment & Tavily) và **JobRadar Full (Hybrid + Enrichment)**."
        ),
        (
            "- **Quy tắc Ground-Truth Relevance:** Một gợi ý trong Top 5 được coi là phù hợp khi "
            "tiêu đề/kỹ năng khớp chứa ít nhất 1 từ khóa chuyên ngành và `job_level` nằm trong "
            "phạm vi liền kề ±1 bậc (`intern <-> fresher <-> junior <-> mid <-> senior`)."
        ),
        "",
        "## 2. Bảng So sánh Tổng hợp: Baseline (CV-Only) vs JobRadar Full",
        "",
        (
            "| Chỉ số đánh giá | Baseline (CV-Only) | JobRadar Full (Hybrid + Enrichment) | "
            "Chênh lệch | % Cải thiện |"
        ),
        "|---|---:|---:|---:|---:|",
        (
            f"| **Precision@5** | {s['baseline_precision_at_5']:.4f} | "
            f"{s['full_precision_at_5']:.4f} | {_fmt_delta(s['precision_at_5_delta'])} | "
            f"{_fmt_pct_change(s['precision_at_5_rel_pct'])} |"
        ),
        (
            f"| **MRR@5** | {s['baseline_mrr_at_5']:.4f} | "
            f"{s['full_mrr_at_5']:.4f} | {_fmt_delta(s['mrr_at_5_delta'])} | "
            f"{_fmt_pct_change(s['mrr_at_5_rel_pct'])} |"
        ),
        (
            f"| **Điểm phù hợp Top-1 (TB)** | {s['baseline_top1_score_mean']:.2f} | "
            f"{s['full_top1_score_mean']:.2f} | {s['top1_score_delta']:+.2f} | "
            f"{_fmt_pct_change(s['top1_score_rel_pct'])} |"
        ),
        (
            f"| **Độ phủ Web Evidence Top-5** | "
            f"{s['baseline_web_evidence_top5_ratio'] * 100:.1f}% | "
            f"{s['full_web_evidence_top5_ratio'] * 100:.1f}% | "
            f"{(s['full_web_evidence_top5_ratio'] - s['baseline_web_evidence_top5_ratio']) * 100:+.1f}% | "
            "— |"
        ),
        (
            f"| **Số kỹ năng trung bình / hồ sơ** | {s['avg_cv_skills']:.2f} | "
            f"{s['avg_full_skills']:.2f} | "
            f"{s['avg_full_skills'] - s['avg_cv_skills']:+.2f} | "
            f"+{s['avg_new_github_skills']:.2f} từ GitHub |"
        ),
        (
            f"| **Kỹ năng xác thực GitHub (TB)** | 0.00 (0.0%) | "
            f"{s['avg_github_verified_skills']:.2f} ({s['github_verified_ratio'] * 100:.1f}%) | "
            f"+{s['avg_github_verified_skills']:.2f} | — |"
        ),
        (
            f"| **Tổng độ trễ trung bình (s)** | {base_total:.2f}s | {full_total:.2f}s | "
            f"{total_lat_delta:+.2f}s | {_fmt_pct_change(total_lat_pct)} |"
        ),
        "",
        "## 3. Phân tích Độ trễ theo Giai đoạn & Dịch chuyển Xếp hạng Top-5",
        "",
        "| Giai đoạn xử lý | Trung bình (Mean) | Trung vị (Median) |",
        "|---|---:|---:|",
        f"| Stage 1 — Trích xuất CV (dùng chung) | {lat_mean['extraction']:.2f}s | {lat_med['extraction']:.2f}s |",
        f"| Stage 2 — Làm giàu GitHub / Jina (Full) | {lat_mean['enrichment']:.2f}s | {lat_med['enrichment']:.2f}s |",
        f"| Embedding (Baseline) | {lat_mean['baseline_embed']:.2f}s | {lat_med['baseline_embed']:.2f}s |",
        f"| Embedding (Full) | {lat_mean['full_embed']:.2f}s | {lat_med['full_embed']:.2f}s |",
        f"| Matching Agent (Baseline, không Tavily) | {lat_mean['baseline_match']:.2f}s | {lat_med['baseline_match']:.2f}s |",
        f"| Matching Agent (Full, có Tavily) | {lat_mean['full_match']:.2f}s | {lat_med['full_match']:.2f}s |",
        "",
        f"- **Top-5 Jaccard trung bình (Baseline vs Full):** {s['mean_top5_jaccard']:.4f}",
        f"- **Số công ty mới xuất hiện vào Top-5 (TB):** {s['mean_new_companies_in_top5']:.2f}",
        f"- **Độ dịch chuyển thứ hạng trung bình (`avg_rank_delta`):** {s['mean_rank_delta']:.2f}",
        "",
        "## 4. Kết quả Chi tiết Từng Hồ sơ CV",
        "",
        (
            "| CV ID | Bậc | Status | P@5 (Base -> Full) | MRR@5 (Base -> Full) | "
            "GH Verified / New | Top-1 Full (Score) | Latency (Base / Full) |"
        ),
        "|---|---|---|---|---|---:|---|---|",
    ]

    for r in records:
        if r.get("status") != "completed":
            lines.append(
                f"| `{r['cv_id']}` | `{r['expected_level']}` | `failed` | — | — | — | "
                f"— | {r.get('error', '')} |"
            )
            continue
        lines.append(
            f"| `{r['cv_id']}` | `{r['expected_level']}` | `ok` | "
            f"{r['baseline_precision_at_5']:.2f} -> {r['full_precision_at_5']:.2f} | "
            f"{r['baseline_mrr_at_5']:.2f} -> {r['full_mrr_at_5']:.2f} | "
            f"{r['github_verified_skills']} / +{r['new_github_skills']} | "
            f"{r['full_top1_company']} ({r['full_top1_score']}) | "
            f"{r['baseline_total_s']:.2f}s / {r['full_total_s']:.2f}s |"
        )

    lines.append("")
    return "\n".join(lines)


def generate_benchmark_reports(
    run_result: Mapping[str, Any],
    output_dir: Path,
) -> tuple[Path, Path]:
    """Write detailed CSV results and summary Vietnamese Markdown report to output_dir."""
    output_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = output_dir / f"benchmark_results_{ts}.csv"
    md_path = output_dir / f"benchmark_summary_{ts}.md"

    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDNAMES, extrasaction="ignore")
        writer.writeheader()
        for record in run_result["records"]:
            writer.writerow(record)

    md_path.write_text(_build_markdown_report(run_result), encoding="utf-8")
    return csv_path, md_path

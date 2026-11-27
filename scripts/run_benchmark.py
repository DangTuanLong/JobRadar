"""CLI entrypoint for running the JobRadar Quantitative Ablation Study benchmark."""

from __future__ import annotations

import argparse
import asyncio
import logging
from pathlib import Path

from benchmark.report_generator import generate_benchmark_reports
from benchmark.runner import DEFAULT_DELAY_SECONDS, DEFAULT_OUTPUT_DIR, run_benchmark

logger = logging.getLogger("run_benchmark")


def _configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
        datefmt="%H:%M:%S",
    )
    for noisy in ("httpx", "httpcore", "hpack"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run Baseline (CV-Only) vs JobRadar Full Ablation Study benchmark."
    )
    parser.add_argument(
        "--mode",
        choices=("offline", "live"),
        default="offline",
        help="Execution mode: offline deterministic simulation or live backend pipeline.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Maximum number of CVs from the 12-CV benchmark dataset to run.",
    )
    parser.add_argument(
        "--delay-seconds",
        type=float,
        default=DEFAULT_DELAY_SECONDS,
        help="Delay in seconds between live CV runs to protect free-tier RPM limits.",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume from checkpoint.jsonl and skip already completed CVs.",
    )
    parser.add_argument(
        "--extraction-model",
        type=str,
        default=None,
        help="Optional Gemini extraction model override for live runs.",
    )
    parser.add_argument(
        "--matching-model",
        type=str,
        default=None,
        help="Optional Gemini matching model override for live runs.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="Directory where CSV, Markdown summary, and checkpoint files are written.",
    )
    parser.add_argument(
        "--checkpoint-file",
        type=Path,
        default=None,
        help="Optional explicit JSONL checkpoint file path.",
    )
    return parser.parse_args()


def main() -> None:
    _configure_logging()
    args = _parse_args()
    run_result = asyncio.run(
        run_benchmark(
            mode=args.mode,
            limit=args.limit,
            delay_seconds=args.delay_seconds,
            resume=args.resume,
            extraction_model=args.extraction_model,
            matching_model=args.matching_model,
            output_dir=args.output_dir,
            checkpoint_path=args.checkpoint_file,
        )
    )
    csv_path, md_path = generate_benchmark_reports(run_result, args.output_dir)
    summary = run_result["summary"]
    logger.info(
        "Benchmark complete (%s): %d/%d CVs | P@5 %.4f -> %.4f | MRR@5 %.4f -> %.4f",
        args.mode,
        summary["completed_cvs"],
        summary["total_cvs"],
        summary["baseline_precision_at_5"],
        summary["full_precision_at_5"],
        summary["baseline_mrr_at_5"],
        summary["full_mrr_at_5"],
    )
    logger.info("CSV report: %s", csv_path)
    logger.info("Markdown report: %s", md_path)


if __name__ == "__main__":
    main()

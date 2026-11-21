"""Seed script for JobRadar sample knowledge base.

Generates companies and job postings, embeds job data with Gemini embedding-001 (768-dim),
upserts idempotently to Supabase pgvector, and verifies retrieval with match_job_postings RPC.
"""

from __future__ import annotations

import argparse
import json
import logging
import math
import os
import sys
import time
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from google import genai
from google.genai import types
from supabase import Client, create_client

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("seed_knowledge_base")

EMBEDDING_MODEL = "gemini-embedding-001"
EMBEDDING_DIM = 768
BATCH_SIZE = 16
BATCH_DELAY_SECONDS = 1.5


def l2_normalize(vector: list[float]) -> list[float]:
    """Normalize a vector to unit length (L2 norm)."""
    norm = math.sqrt(sum(v * v for v in vector))
    return [v / norm for v in vector] if norm > 0 else vector


def embed_texts(
    client: genai.Client,
    texts: list[str],
    task_type: str = "RETRIEVAL_DOCUMENT",
) -> list[list[float]]:
    """Embed texts using Google GenAI SDK with 768 output dimensions and L2 normalization."""
    response = client.models.embed_content(
        model=EMBEDDING_MODEL,
        contents=texts,
        config=types.EmbedContentConfig(
            task_type=task_type,
            output_dimensionality=EMBEDDING_DIM,
        ),
    )
    if not response.embeddings:
        return []
    return [l2_normalize(list(item.values)) for item in response.embeddings]


def embed_texts_with_retry(
    client: genai.Client,
    texts: list[str],
    task_type: str = "RETRIEVAL_DOCUMENT",
    max_retries: int = 5,
) -> list[list[float]]:
    """Embed texts with exponential backoff on transient/rate-limit errors."""
    for attempt in range(max_retries):
        try:
            return embed_texts(client, texts, task_type=task_type)
        except Exception as exc:
            if attempt == max_retries - 1:
                logger.error("Embedding failed after %d retries: %s", max_retries, exc)
                raise
            err_str = str(exc)
            if "429" in err_str or "RESOURCE_EXHAUSTED" in err_str:
                delay = 32.0 + (attempt * 5.0)
            else:
                delay = (2**attempt) * 2.0
            logger.warning(
                "Gemini transient/rate-limit error: %s. Retrying in %.1fs...", exc, delay
            )
            time.sleep(delay)
    return []


def load_json_data(data_dir: Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Load companies and role templates from JSON files."""
    companies_file = data_dir / "companies.json"
    templates_file = data_dir / "role_templates.json"

    if not companies_file.exists():
        raise FileNotFoundError(f"Companies file not found: {companies_file}")
    if not templates_file.exists():
        raise FileNotFoundError(f"Role templates file not found: {templates_file}")

    with open(companies_file, encoding="utf-8") as f:
        companies = json.load(f)
    with open(templates_file, encoding="utf-8") as f:
        templates = json.load(f)

    return companies, templates


def build_job_embedding_text(company: dict[str, Any], job: dict[str, Any]) -> str:
    """Build structured text representation of a job posting for embedding."""
    required = ", ".join(job.get("required_skills", []))
    nice = ", ".join(job.get("nice_to_have_skills", []))
    stack = ", ".join(company.get("tech_stack", []))
    return (
        f"Job Title: {job['title']}\n"
        f"Level: {job['level']}\n"
        f"Company: {company['name']}\n"
        f"Industry: {company['industry']}\n"
        f"Location: {company['location']}\n"
        f"Required Skills: {required}\n"
        f"Nice-to-have Skills: {nice}\n"
        f"Job Description: {job.get('description', '')}\n"
        f"Company Tech Stack: {stack}\n"
        f"Company Culture: {company.get('culture_summary', '')}"
    )


def match_roles_for_companies(
    companies: list[dict[str, Any]], templates: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Match each company with 4 suitable role templates to generate 100-140 JDs."""
    generated_jobs: list[dict[str, Any]] = []

    for comp_idx, comp in enumerate(companies):
        comp_stack = {t.lower() for t in comp.get("tech_stack", [])}
        industry = comp.get("industry", "").lower()

        # Score templates based on overlap with company tech stack and domain
        scored_templates: list[tuple[float, dict[str, Any]]] = []
        for tmpl in templates:
            score = 0.0
            tags = {t.lower() for t in tmpl.get("tech_tags", [])}
            overlap = comp_stack.intersection(tags)
            score += len(overlap) * 3.0

            domain = tmpl.get("domain", "")
            if (
                ("game" in industry and domain in ("mobile", "qa"))
                or ("fintech" in industry and domain in ("backend", "mobile", "qa", "devops"))
                or ("e-commerce" in industry and domain in ("backend", "frontend", "mobile"))
                or ("saas" in industry and domain in ("backend", "frontend", "fullstack", "devops"))
            ):
                score += 2.0
            elif "ai" in industry and domain in ("ai_data", "backend"):
                score += 3.0
            elif "outsourcing" in industry or "services" in industry:
                score += 1.5

            # Add tie-breaking jitter based on company index to ensure diversity across companies
            jitter = (hash((comp["name"], tmpl["template_id"])) % 100) / 1000.0
            scored_templates.append((score + jitter, tmpl))

        # Sort descending by score
        scored_templates.sort(key=lambda x: x[0], reverse=True)

        # Select 4 distinct role templates with unique (title, level)
        chosen: list[dict[str, Any]] = []
        used_pairs: set[tuple[str, str]] = set()

        for _, tmpl in scored_templates:
            pair = (tmpl["title"], tmpl["level"])
            if pair not in used_pairs:
                used_pairs.add(pair)
                chosen.append(tmpl)
                if len(chosen) == 4:
                    break

        # Fallback if fewer than 4 selected
        if len(chosen) < 4:
            for tmpl in templates:
                pair = (tmpl["title"], tmpl["level"])
                if pair not in used_pairs:
                    used_pairs.add(pair)
                    chosen.append(tmpl)
                    if len(chosen) == 4:
                        break

        for tmpl in chosen:
            clean_website = comp.get("website", "").rstrip("/")
            generated_jobs.append(
                {
                    "company_name": comp["name"],
                    "title": tmpl["title"],
                    "level": tmpl["level"],
                    "required_skills": tmpl.get("required_skills", []),
                    "nice_to_have_skills": tmpl.get("nice_to_have_skills", []),
                    "description": tmpl.get("description", ""),
                    "source_url": f"{clean_website}/careers",
                    "is_active": True,
                }
            )

    return generated_jobs


def run_verification(client: genai.Client, supabase: Client) -> None:
    """Run RPC verification query and log top matches."""
    query_text = (
        "Fresher Android Mobile Developer proficient in Kotlin, Jetpack Compose, "
        "Coroutines, Retrofit, MVVM, Supabase, Git."
    )
    logger.info("Generating query embedding for verification test profile...")
    query_vec = embed_texts_with_retry(client, [query_text], task_type="RETRIEVAL_QUERY")[0]

    logger.info("Calling Supabase RPC 'match_job_postings' (threshold=0.40, count=5)...")
    rpc_res = supabase.rpc(
        "match_job_postings",
        {
            "query_embedding": query_vec,
            "match_threshold": 0.40,
            "match_count": 5,
        },
    ).execute()

    results: list[dict[str, Any]] = rpc_res.data or []
    if not results:
        logger.warning("RPC returned 0 results. Check threshold or data in database.")
        return

    logger.info("--- TOP 5 MATCHED JOBS VIA RPC ---")
    for i, item in enumerate(results, start=1):
        logger.info(
            "%d. [Similarity: %.4f] %s at %s (Level: %s | Location: %s)",
            i,
            item["similarity"],
            item["job_title"],
            item["company_name"],
            item["job_level"],
            item["company_location"],
        )
    logger.info("--- VERIFICATION COMPLETED SUCCESSFULLY ---")


def main() -> None:
    """Main CLI entrypoint."""
    parser = argparse.ArgumentParser(description="Seed JobRadar Knowledge Base")
    parser.add_argument("--dry-run", action="store_true", help="Validate data count without API calls")
    parser.add_argument(
        "--skip-existing",
        action="store_true",
        default=True,
        help="Skip embedding for jobs already in DB with embeddings (default: True)",
    )
    parser.add_argument(
        "--verify-only", action="store_true", help="Only run RPC verification test"
    )
    args = parser.parse_args()

    data_dir = Path(__file__).resolve().parent / "data"
    companies, templates = load_json_data(data_dir)
    logger.info("Loaded %d companies and %d role templates.", len(companies), len(templates))

    paired_jobs = match_roles_for_companies(companies, templates)
    logger.info("Generated %d job postings across %d companies.", len(paired_jobs), len(companies))

    if args.dry_run:
        logger.info("--- DRY-RUN SUMMARY ---")
        logger.info("Companies to upsert: %d", len(companies))
        logger.info("Total Job Postings: %d", len(paired_jobs))
        level_counts: dict[str, int] = {}
        for j in paired_jobs:
            level_counts[j["level"]] = level_counts.get(j["level"], 0) + 1
        for level, count in sorted(level_counts.items()):
            logger.info("  Level '%s': %d jobs", level, count)
        logger.info("Dry run complete. No write or embedding calls made.")
        return

    project_root = Path(__file__).resolve().parent.parent
    load_dotenv(project_root / ".env")

    supabase_url = os.getenv("SUPABASE_URL", "").strip()
    supabase_secret_key = os.getenv("SUPABASE_SECRET_KEY", "").strip()
    gemini_api_key = os.getenv("GEMINI_API_KEY", "").strip()

    if not supabase_url or not supabase_secret_key or not gemini_api_key:
        logger.error("Missing required environment variables in .env:")
        if not supabase_url:
            logger.error(" - SUPABASE_URL is not set")
        if not supabase_secret_key:
            logger.error(" - SUPABASE_SECRET_KEY is not set")
        if not gemini_api_key:
            logger.error(" - GEMINI_API_KEY is not set")
        sys.exit(1)

    genai_client = genai.Client(api_key=gemini_api_key)
    supabase_client: Client = create_client(supabase_url, supabase_secret_key)

    if args.verify_only:
        logger.info("Running in --verify-only mode...")
        run_verification(genai_client, supabase_client)
        return

    # 1. Upsert Companies
    logger.info("Upserting %d companies to Supabase...", len(companies))
    supabase_client.table("companies").upsert(companies, on_conflict="name").execute()

    # 2. Fetch Company IDs
    comp_res = supabase_client.table("companies").select("id, name").execute()
    comp_map = {row["name"]: row["id"] for row in (comp_res.data or [])}
    logger.info("Mapped %d companies to database IDs.", len(comp_map))

    # 3. Check Existing Jobs if --skip-existing
    existing_job_embeddings: set[tuple[str, str, str]] = set()
    if args.skip_existing:
        existing_res = (
            supabase_client.table("job_postings")
            .select("company_id, title, level, embedding")
            .execute()
        )
        for row in existing_res.data or []:
            if row.get("embedding"):
                existing_job_embeddings.add((row["company_id"], row["title"], row["level"]))
        logger.info(
            "Found %d existing job postings with embeddings in database.",
            len(existing_job_embeddings),
        )

    # 4. Prepare Jobs for Embedding
    company_by_name = {c["name"]: c for c in companies}
    jobs_to_embed: list[dict[str, Any]] = []
    embedding_texts: list[str] = []

    for item in paired_jobs:
        c_name = item["company_name"]
        company_id = comp_map.get(c_name)
        if not company_id:
            logger.warning("Company '%s' not found in DB ID map, skipping job.", c_name)
            continue

        pair_key = (company_id, item["title"], item["level"])
        if args.skip_existing and pair_key in existing_job_embeddings:
            continue

        comp_dict = company_by_name[c_name]
        job_record = {
            "company_id": company_id,
            "title": item["title"],
            "level": item["level"],
            "required_skills": item["required_skills"],
            "nice_to_have_skills": item["nice_to_have_skills"],
            "description": item["description"],
            "source_url": item["source_url"],
            "is_active": True,
        }
        text_repr = build_job_embedding_text(comp_dict, job_record)
        jobs_to_embed.append(job_record)
        embedding_texts.append(text_repr)

    logger.info("Job postings requiring new embeddings: %d", len(jobs_to_embed))

    # 5. Batch Embed and Upsert
    if jobs_to_embed:
        total_batches = (len(jobs_to_embed) + BATCH_SIZE - 1) // BATCH_SIZE
        for b_idx in range(total_batches):
            start = b_idx * BATCH_SIZE
            end = min(start + BATCH_SIZE, len(jobs_to_embed))
            batch_texts = embedding_texts[start:end]
            batch_records = jobs_to_embed[start:end]

            logger.info(
                "Embedding batch %d/%d (items %d-%d)...",
                b_idx + 1,
                total_batches,
                start + 1,
                end,
            )
            embeddings = embed_texts_with_retry(
                genai_client, batch_texts, task_type="RETRIEVAL_DOCUMENT"
            )

            for rec, emb in zip(batch_records, embeddings, strict=True):
                rec["embedding"] = emb

            logger.info("Upserting batch %d/%d to 'job_postings'...", b_idx + 1, total_batches)
            supabase_client.table("job_postings").upsert(
                batch_records, on_conflict="company_id,title,level"
            ).execute()

            if b_idx < total_batches - 1:
                time.sleep(BATCH_DELAY_SECONDS)

        logger.info("Successfully embedded and upserted %d job postings.", len(jobs_to_embed))
    else:
        logger.info("All job postings are already embedded and up-to-date.")

    # 6. Run RPC Verification
    run_verification(genai_client, supabase_client)


if __name__ == "__main__":
    main()

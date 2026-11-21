"""Async repository for pgvector job search RPC and match_results persistence."""

from __future__ import annotations

from typing import Any

from supabase import Client

from app.core.logging import get_logger
from app.db.supabase_client import execute_supabase_query, get_supabase_client

logger = get_logger(__name__)

TABLE_ANALYSIS_JOBS = "analysis_jobs"
TABLE_MATCH_RESULTS = "match_results"
RPC_MATCH_JOB_POSTINGS = "match_job_postings"
DEFAULT_MATCH_THRESHOLD = 0.45
DEFAULT_MATCH_COUNT = 15


class MatchRepository:
    """Data access layer for vector job retrieval and match_results."""

    def __init__(self, client: Client | None = None) -> None:
        self._client = client

    @property
    def client(self) -> Client:
        return self._client if self._client is not None else get_supabase_client()

    async def search_job_postings(
        self,
        query_embedding: list[float],
        match_threshold: float = DEFAULT_MATCH_THRESHOLD,
        match_count: int = DEFAULT_MATCH_COUNT,
        filter_level: str | None = None,
    ) -> list[dict[str, Any]]:
        """Retrieve candidate job postings via Supabase pgvector RPC."""
        params: dict[str, Any] = {
            "query_embedding": query_embedding,
            "match_threshold": match_threshold,
            "match_count": match_count,
            "filter_level": filter_level,
        }
        response = await execute_supabase_query(
            "search_job_postings",
            lambda: self.client.rpc(RPC_MATCH_JOB_POSTINGS, params).execute(),
        )
        return list(response.data or [])

    async def save_match_results(
        self,
        job_id: str,
        profile_id: str,
        results: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """Persist evaluated match results for an analysis job."""
        if not results:
            return []

        rows: list[dict[str, Any]] = []
        for item in results:
            row: dict[str, Any] = {
                "job_id": job_id,
                "profile_id": profile_id,
                "company_id": item["company_id"],
                "job_posting_id": item.get("job_posting_id"),
                "match_score": int(item["match_score"]),
                "score_breakdown": (
                    item["score_breakdown"] if item.get("score_breakdown") is not None else {}
                ),
                "matched_skills": (
                    item["matched_skills"] if item.get("matched_skills") is not None else []
                ),
                "missing_skills": (
                    item["missing_skills"] if item.get("missing_skills") is not None else []
                ),
                "reasoning": item.get("reasoning"),
                "web_evidence": (
                    item["web_evidence"] if item.get("web_evidence") is not None else {}
                ),
            }
            rows.append(row)

        response = await execute_supabase_query(
            "save_match_results",
            lambda: self.client.table(TABLE_MATCH_RESULTS).insert(rows).execute(),
        )
        return list(response.data or [])

    async def get_match_results_by_job(
        self,
        job_id: str,
        user_id: str,
    ) -> list[dict[str, Any]]:
        """Fetch match results for a job owned by the user, sorted by match_score desc."""
        job_ownership = await execute_supabase_query(
            "verify_job_ownership",
            lambda: (
                self.client.table(TABLE_ANALYSIS_JOBS)
                .select("id")
                .eq("id", job_id)
                .eq("user_id", user_id)
                .limit(1)
                .execute()
            ),
        )
        if not job_ownership.data:
            return []

        select_query = (
            "*, "
            "companies(id, name, website, industry, size_range, location, tech_stack), "
            "job_postings(id, title, level, source_url)"
        )
        response = await execute_supabase_query(
            "get_match_results_by_job",
            lambda: (
                self.client.table(TABLE_MATCH_RESULTS)
                .select(select_query)
                .eq("job_id", job_id)
                .order("match_score", desc=True)
                .execute()
            ),
        )
        return list(response.data or [])

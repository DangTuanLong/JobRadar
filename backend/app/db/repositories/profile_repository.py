"""Async repository for the candidate_profiles table."""

from __future__ import annotations

from typing import Any

from supabase import Client

from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.db.supabase_client import execute_supabase_query, get_supabase_client

logger = get_logger(__name__)

TABLE_CANDIDATE_PROFILES = "candidate_profiles"

PROFILE_WRITABLE_FIELDS = frozenset(
    {
        "full_name",
        "headline",
        "years_of_experience",
        "cv_file_url",
        "skills",
        "experiences",
        "external_links",
        "enrichment_summary",
        "profile_summary",
        "embedding",
    }
)
PROFILE_NOT_NULL_FIELDS = frozenset(
    {
        "years_of_experience",
        "skills",
        "experiences",
        "external_links",
        "enrichment_summary",
    }
)


class ProfileRepository:
    """Data access layer for candidate_profiles records."""

    def __init__(self, client: Client | None = None) -> None:
        self._client = client

    @property
    def client(self) -> Client:
        return self._client if self._client is not None else get_supabase_client()

    async def get_latest_profile(self, user_id: str) -> dict[str, Any] | None:
        """Fetch the most recently updated candidate profile for a user."""
        response = await execute_supabase_query(
            "get_latest_profile",
            lambda: (
                self.client.table(TABLE_CANDIDATE_PROFILES)
                .select("*")
                .eq("user_id", user_id)
                .order("updated_at", desc=True)
                .limit(1)
                .execute()
            ),
        )
        rows = list(response.data or [])
        return rows[0] if rows else None

    async def create_profile(
        self,
        user_id: str,
        data: dict[str, Any],
    ) -> dict[str, Any]:
        """Insert a new candidate profile owned by the user."""
        payload: dict[str, Any] = {
            key: value
            for key, value in data.items()
            if key in PROFILE_WRITABLE_FIELDS and value is not None
        }
        payload["user_id"] = user_id

        response = await execute_supabase_query(
            "create_profile",
            lambda: self.client.table(TABLE_CANDIDATE_PROFILES).insert(payload).execute(),
        )
        rows = list(response.data or [])
        if not rows:
            logger.error("create_profile returned no rows for user_id=%s", user_id)
            raise AppException(
                status_code=500,
                code="DB_INSERT_FAILED",
                message="Failed to create candidate profile.",
            )
        return rows[0]

    async def update_profile(
        self,
        profile_id: str,
        user_id: str,
        data: dict[str, Any],
    ) -> dict[str, Any] | None:
        """Update an existing candidate profile owned by the user."""
        payload: dict[str, Any] = {
            key: value
            for key, value in data.items()
            if key in PROFILE_WRITABLE_FIELDS
            and not (key in PROFILE_NOT_NULL_FIELDS and value is None)
        }
        if not payload:
            response = await execute_supabase_query(
                "get_profile_by_id",
                lambda: (
                    self.client.table(TABLE_CANDIDATE_PROFILES)
                    .select("*")
                    .eq("id", profile_id)
                    .eq("user_id", user_id)
                    .limit(1)
                    .execute()
                ),
            )
            rows = list(response.data or [])
            return rows[0] if rows else None

        response = await execute_supabase_query(
            "update_profile",
            lambda: (
                self.client.table(TABLE_CANDIDATE_PROFILES)
                .update(payload)
                .eq("id", profile_id)
                .eq("user_id", user_id)
                .execute()
            ),
        )
        rows = list(response.data or [])
        if not rows:
            logger.warning(
                "update_profile matched no record for profile_id=%s user_id=%s",
                profile_id,
                user_id,
            )
            return None
        return rows[0]

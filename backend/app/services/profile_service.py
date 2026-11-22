"""Business logic for retrieving and updating candidate profiles with re-embedding."""

from __future__ import annotations

from typing import Any

from google import genai

from app.core.constants import EMBEDDING_TASK_QUERY, SKILL_LEVEL_RANK
from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.db.repositories.profile_repository import ProfileRepository
from app.schemas.profile import (
    CandidateProfileResponse,
    CandidateProfileUpdate,
    SkillItem,
)
from app.services.embedding_service import build_profile_embedding_text, generate_embedding

logger = get_logger(__name__)

PROFILE_NOT_FOUND_MESSAGE = "Chưa tìm thấy hồ sơ năng lực. Vui lòng tải lên CV trước."
_EMBEDDING_TRIGGER_FIELDS = frozenset(
    {
        "headline",
        "years_of_experience",
        "skills",
        "experiences",
        "profile_summary",
    }
)


def _deduplicate_profile_skills(skills: list[SkillItem]) -> list[dict[str, Any]]:
    """Deduplicate edited skills case-insensitively, keeping highest level and merged sources."""
    merged: dict[str, SkillItem] = {}
    for skill in skills:
        key = skill.name.strip().lower()
        if not key:
            continue

        cleaned_sources: list[str] = []
        for source in skill.verified_by:
            val = source.strip()
            if val and val not in cleaned_sources:
                cleaned_sources.append(val)

        existing = merged.get(key)
        if existing is None:
            merged[key] = SkillItem(
                name=skill.name.strip(),
                category=skill.category,
                level=skill.level,
                verified_by=cleaned_sources,
            )
            continue

        if SKILL_LEVEL_RANK[skill.level] > SKILL_LEVEL_RANK[existing.level]:
            existing.level = skill.level
        if existing.category == "other" and skill.category != "other":
            existing.category = skill.category
        for source in cleaned_sources:
            if source not in existing.verified_by:
                existing.verified_by.append(source)

    return [item.model_dump() for item in merged.values()]


class ProfileService:
    """Handles candidate profile retrieval, normalization, and embedding refresh."""

    def __init__(
        self,
        *,
        profile_repo: ProfileRepository | None = None,
        gemini_client: genai.Client | None = None,
    ) -> None:
        self._profile_repo = profile_repo if profile_repo is not None else ProfileRepository()
        self._gemini_client = gemini_client

    async def get_my_profile(self, user_id: str) -> CandidateProfileResponse:
        """Fetch the authenticated user's latest candidate profile without raw embedding."""
        profile = await self._profile_repo.get_latest_profile(user_id)
        if profile is None:
            raise AppException(
                status_code=404,
                code="PROFILE_NOT_FOUND",
                message=PROFILE_NOT_FOUND_MESSAGE,
            )
        return CandidateProfileResponse.model_validate(profile)

    async def update_my_profile(
        self,
        user_id: str,
        payload: CandidateProfileUpdate,
    ) -> CandidateProfileResponse:
        """Update the user's latest profile and regenerate embedding on semantic edits."""
        existing = await self._profile_repo.get_latest_profile(user_id)
        if existing is None:
            raise AppException(
                status_code=404,
                code="PROFILE_NOT_FOUND",
                message=PROFILE_NOT_FOUND_MESSAGE,
            )

        update_data: dict[str, Any] = {}
        provided_fields = payload.model_fields_set

        if "full_name" in provided_fields:
            update_data["full_name"] = (
                payload.full_name.strip() if isinstance(payload.full_name, str) else None
            )
        if "headline" in provided_fields:
            update_data["headline"] = (
                payload.headline.strip() if isinstance(payload.headline, str) else None
            )
        if "years_of_experience" in provided_fields and payload.years_of_experience is not None:
            update_data["years_of_experience"] = payload.years_of_experience
        if "skills" in provided_fields and payload.skills is not None:
            update_data["skills"] = _deduplicate_profile_skills(payload.skills)
        if "experiences" in provided_fields and payload.experiences is not None:
            update_data["experiences"] = [exp.model_dump() for exp in payload.experiences]
        if "external_links" in provided_fields and payload.external_links is not None:
            update_data["external_links"] = payload.external_links.model_dump()
        if "profile_summary" in provided_fields:
            update_data["profile_summary"] = (
                payload.profile_summary.strip()
                if isinstance(payload.profile_summary, str)
                else None
            )

        if bool(update_data.keys() & _EMBEDDING_TRIGGER_FIELDS):
            merged_profile = {**existing, **update_data}
            embedding_text = build_profile_embedding_text(merged_profile)
            if embedding_text.strip():
                logger.info("Regenerating 768-dim profile embedding for user_id=%s", user_id)
                update_data["embedding"] = await generate_embedding(
                    embedding_text,
                    task_type=EMBEDDING_TASK_QUERY,
                    client=self._gemini_client,
                )

        profile_id = str(existing["id"])
        updated = await self._profile_repo.update_profile(profile_id, user_id, update_data)
        if updated is None:
            raise AppException(
                status_code=404,
                code="PROFILE_NOT_FOUND",
                message=PROFILE_NOT_FOUND_MESSAGE,
            )
        return CandidateProfileResponse.model_validate(updated)

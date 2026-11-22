"""Candidate profile retrieval and manual editing endpoints."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.v1.cv import get_profile_repository
from app.core.security import CurrentUser
from app.db.repositories.profile_repository import ProfileRepository
from app.schemas.profile import CandidateProfileResponse, CandidateProfileUpdate
from app.schemas.response import ApiResponse
from app.services.profile_service import ProfileService

router = APIRouter(prefix="/profiles", tags=["profiles"])


def get_profile_service(
    profile_repo: Annotated[ProfileRepository, Depends(get_profile_repository)],
) -> ProfileService:
    """Provide ProfileService wired with ProfileRepository."""
    return ProfileService(profile_repo=profile_repo)


ProfileServiceDep = Annotated[ProfileService, Depends(get_profile_service)]


@router.get("/me", response_model=ApiResponse[CandidateProfileResponse])
async def get_my_profile(
    current_user: CurrentUser,
    profile_service: ProfileServiceDep,
) -> ApiResponse[CandidateProfileResponse]:
    """Return the authenticated user's latest candidate profile."""
    profile = await profile_service.get_my_profile(current_user.user_id)
    return ApiResponse(success=True, data=profile, error=None)


@router.put("/me", response_model=ApiResponse[CandidateProfileResponse])
async def update_my_profile(
    payload: CandidateProfileUpdate,
    current_user: CurrentUser,
    profile_service: ProfileServiceDep,
) -> ApiResponse[CandidateProfileResponse]:
    """Update the authenticated user's profile and refresh vector embedding if needed."""
    updated = await profile_service.update_my_profile(current_user.user_id, payload)
    return ApiResponse(success=True, data=updated, error=None)

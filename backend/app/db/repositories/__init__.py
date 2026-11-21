"""Database repository classes for JobRadar domain tables."""

from app.db.repositories.job_repository import JobRepository
from app.db.repositories.match_repository import MatchRepository
from app.db.repositories.profile_repository import ProfileRepository

__all__ = [
    "JobRepository",
    "ProfileRepository",
    "MatchRepository",
]

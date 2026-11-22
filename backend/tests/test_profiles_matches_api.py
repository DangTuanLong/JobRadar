"""Offline API unit tests for /api/v1/profiles/me and /api/v1/matches endpoints."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any
from uuid import uuid4

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app.api.v1.cv import get_job_repository, get_profile_repository
from app.api.v1.matches import get_match_repository, get_matching_service
from app.core.config import settings
from app.core.security import get_jwks_client
from app.main import app

TEST_SUPABASE_URL = "https://test-project.supabase.co"


class _FakeJobRepo:
    def __init__(self) -> None:
        self.jobs: dict[str, dict[str, Any]] = {}
        self.status_history: list[str] = []

    async def create_job(self, user_id: str, profile_id: str | None = None) -> dict[str, Any]:
        job_id, now = str(uuid4()), datetime.now(tz=UTC).isoformat()
        row = {
            "id": job_id,
            "user_id": user_id,
            "profile_id": profile_id,
            "status": "PENDING",
            "progress_percent": 0,
            "current_step_message": "Queued",
            "created_at": now,
            "updated_at": now,
        }
        self.jobs[job_id] = row
        return dict(row)

    async def get_job_by_id(self, job_id: str, user_id: str) -> dict[str, Any] | None:
        job = self.jobs.get(job_id)
        return dict(job) if job and job["user_id"] == user_id else None

    async def update_job_status(
        self, job_id: str, status: str, progress_percent: int, **kwargs: Any
    ) -> dict[str, Any] | None:
        job = self.jobs.get(job_id)
        if job is None:
            return None
        self.status_history.append(status)
        job["status"], job["progress_percent"] = status, progress_percent
        job.update({k: v for k, v in kwargs.items() if v is not None})
        return dict(job)


class _FakeProfileRepo:
    def __init__(self) -> None:
        self.profiles_by_user: dict[str, dict[str, Any]] = {}
        self.last_update_payload: dict[str, Any] | None = None

    async def get_latest_profile(self, user_id: str) -> dict[str, Any] | None:
        prof = self.profiles_by_user.get(user_id)
        return dict(prof) if prof else None

    async def update_profile(
        self, profile_id: str, user_id: str, data: dict[str, Any]
    ) -> dict[str, Any] | None:
        prof = self.profiles_by_user.get(user_id)
        if prof is None or str(prof["id"]) != profile_id:
            return None
        self.last_update_payload = dict(data)
        prof.update(data)
        return dict(prof)


class _FakeMatchRepo:
    def __init__(self) -> None:
        self.results_by_job: dict[str, list[dict[str, Any]]] = {}

    async def get_match_results_by_job(self, job_id: str, user_id: str) -> list[dict[str, Any]]:
        return list(self.results_by_job.get(job_id, []))


class _FakeMatchingService:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, str]] = []

    async def run_for_job(self, job_id: str, profile_id: str, user_id: str) -> list[dict[str, Any]]:
        self.calls.append((job_id, profile_id, user_id))
        return []


def _token(private_key: ec.EllipticCurvePrivateKey, user_id: str = "user-1") -> str:
    now = datetime.now(tz=UTC)
    claims = {
        "sub": user_id,
        "email": f"{user_id}@example.com",
        "aud": "authenticated",
        "iss": f"{TEST_SUPABASE_URL}/auth/v1",
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(minutes=15)).timestamp()),
        "role": "authenticated",
    }
    return jwt.encode(claims, private_key, algorithm="ES256", headers={"kid": "test-kid"})


def _seed_profile(user_id: str = "user-1") -> dict[str, Any]:
    return {
        "id": str(uuid4()),
        "user_id": user_id,
        "full_name": "Nguyen Van A",
        "headline": "Android Developer",
        "years_of_experience": 1.5,
        "cv_file_url": "cvs/user-1/job_cv.pdf",
        "skills": [
            {"name": "Kotlin", "category": "language", "level": "advanced", "verified_by": ["cv"]}
        ],
        "experiences": [],
        "external_links": {"github": "https://github.com/example-dev"},
        "enrichment_summary": {"github_username": "example-dev"},
        "profile_summary": "Android Kotlin developer.",
        "embedding": [0.1] * 768,
    }


def _match_row(
    mid: str, job_id: str, score: int, company: dict[str, str], posting: dict[str, str]
) -> dict[str, Any]:
    return {
        "id": mid,
        "job_id": job_id,
        "profile_id": "p1",
        "company_id": f"c-{mid}",
        "job_posting_id": f"jp-{mid}",
        "match_score": score,
        "score_breakdown": {"skills": score, "experience": score, "projects": score},
        "matched_skills": ["Kotlin"],
        "missing_skills": ["Compose"],
        "reasoning": "Phù hợp tốt.",
        "web_evidence": [{"title": "Blog", "url": "https://beta.vn", "snippet": "Tech"}],
        "companies": company,
        "job_postings": posting,
    }


TestCtx = tuple[
    TestClient, _FakeJobRepo, _FakeProfileRepo, _FakeMatchRepo, _FakeMatchingService, Any
]


@pytest.fixture()
def api_ctx(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestCtx]:
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_key = private_key.public_key()
    get_jwks_client.cache_clear()
    monkeypatch.setattr(settings, "SUPABASE_URL", TEST_SUPABASE_URL)
    jwks = SimpleNamespace(get_signing_key_from_jwt=lambda _tok: SimpleNamespace(key=public_key))
    monkeypatch.setattr("app.core.security.get_jwks_client", lambda: jwks)

    jobs, profiles, matches, svc = (
        _FakeJobRepo(),
        _FakeProfileRepo(),
        _FakeMatchRepo(),
        _FakeMatchingService(),
    )
    app.dependency_overrides[get_job_repository] = lambda: jobs
    app.dependency_overrides[get_profile_repository] = lambda: profiles
    app.dependency_overrides[get_match_repository] = lambda: matches
    app.dependency_overrides[get_matching_service] = lambda: svc
    try:
        with TestClient(app) as client:
            yield client, jobs, profiles, matches, svc, private_key
    finally:
        app.dependency_overrides.clear()
        get_jwks_client.cache_clear()


def test_get_my_profile_200_404_and_401(api_ctx: TestCtx) -> None:
    client, _, profiles, _, _, key = api_ctx
    assert client.get("/api/v1/profiles/me").status_code == 401

    headers = {"Authorization": f"Bearer {_token(key, 'user-1')}"}
    missing = client.get("/api/v1/profiles/me", headers=headers)
    assert missing.status_code == 404 and missing.json()["error"]["code"] == "PROFILE_NOT_FOUND"

    profiles.profiles_by_user["user-1"] = _seed_profile("user-1")
    ok = client.get("/api/v1/profiles/me", headers=headers)
    assert ok.status_code == 200
    assert ok.json()["data"]["full_name"] == "Nguyen Van A"
    assert "embedding" not in ok.json()["data"]


def test_put_my_profile_200_reembeds_404_and_422(
    api_ctx: TestCtx, monkeypatch: pytest.MonkeyPatch
) -> None:
    client, _, profiles, _, _, key = api_ctx
    headers = {"Authorization": f"Bearer {_token(key, 'user-1')}"}
    embed_calls: list[str] = []

    async def fake_embed(text: str, **_kwargs: Any) -> list[float]:
        embed_calls.append(text)
        return [0.25] * 768

    monkeypatch.setattr("app.services.profile_service.generate_embedding", fake_embed)

    no_prof = client.put("/api/v1/profiles/me", headers=headers, json={"headline": "AI"})
    assert no_prof.status_code == 404

    profiles.profiles_by_user["user-1"] = _seed_profile("user-1")
    bad = client.put("/api/v1/profiles/me", headers=headers, json={"years_of_experience": -5})
    assert bad.status_code == 422 and bad.json()["error"]["code"] == "VALIDATION_ERROR"

    skills = [
        {"name": "kotlin", "category": "language", "level": "beginner", "verified_by": ["cv"]},
        {"name": "Kotlin", "category": "language", "level": "expert", "verified_by": ["github"]},
    ]
    ok = client.put(
        "/api/v1/profiles/me",
        headers=headers,
        json={
            "headline": "Senior Android & AI Engineer",
            "skills": skills,
            "external_links": {"github": "github.com/new-dev/", "linkedin": "mailto:bad@ex.com"},
        },
    )
    assert ok.status_code == 200
    body = ok.json()["data"]
    assert len(embed_calls) == 1
    assert profiles.last_update_payload is not None and "embedding" in profiles.last_update_payload
    assert len(body["skills"]) == 1 and body["skills"][0]["level"] == "expert"
    assert body["skills"][0]["verified_by"] == ["cv", "github"]
    assert body["external_links"]["github"] == "https://github.com/new-dev"
    assert body["external_links"]["linkedin"] is None


def test_post_matches_rerun_202_completes_and_404_without_profile(api_ctx: TestCtx) -> None:
    client, jobs, profiles, _, svc, key = api_ctx
    headers = {"Authorization": f"Bearer {_token(key, 'user-1')}"}

    no_prof = client.post("/api/v1/matches/rerun", headers=headers)
    assert no_prof.status_code == 404 and no_prof.json()["error"]["code"] == "PROFILE_NOT_FOUND"

    prof = _seed_profile("user-1")
    profiles.profiles_by_user["user-1"] = prof
    resp = client.post("/api/v1/matches/rerun", headers=headers)
    assert resp.status_code == 202
    job_id = resp.json()["data"]["id"]
    assert jobs.jobs[job_id]["status"] == "COMPLETED"
    assert jobs.jobs[job_id]["progress_percent"] == 100
    assert jobs.status_history == ["MATCHING", "COMPLETED"]
    assert svc.calls == [(job_id, prof["id"], "user-1")]


def test_get_matches_200_sorted_empty_and_404_guards(api_ctx: TestCtx) -> None:
    client, jobs, _, matches, _, key = api_ctx
    owner_headers = {"Authorization": f"Bearer {_token(key, 'owner')}"}
    other_headers = {"Authorization": f"Bearer {_token(key, 'other')}"}

    assert client.get("/api/v1/matches?job_id=bad-uuid", headers=owner_headers).status_code == 404

    job_id = str(uuid4())
    jobs.jobs[job_id] = {"id": job_id, "user_id": "owner", "status": "COMPLETED"}

    idor = client.get(f"/api/v1/matches?job_id={job_id}", headers=other_headers)
    assert idor.status_code == 404 and idor.json()["error"]["code"] == "JOB_NOT_FOUND"

    empty = client.get(f"/api/v1/matches?job_id={job_id}", headers=owner_headers)
    assert empty.status_code == 200 and empty.json()["data"] == []

    matches.results_by_job[job_id] = [
        _match_row(
            "m1",
            job_id,
            72,
            {"name": "Alpha", "website": "https://alpha.vn", "location": "HN"},
            {"title": "Android Junior", "level": "junior"},
        ),
        _match_row(
            "m2",
            job_id,
            91,
            {"name": "Beta", "website": "https://beta.vn", "location": "HCM"},
            {"title": "Android Mid", "level": "mid"},
        ),
    ]
    res = client.get(f"/api/v1/matches?job_id={job_id}", headers=owner_headers)
    assert res.status_code == 200
    items = res.json()["data"]
    assert [i["match_score"] for i in items] == [91, 72]
    assert items[0]["company_name"] == "Beta" and items[0]["job_title"] == "Android Mid"
    assert items[1]["company_location"] == "HN" and items[1]["job_level"] == "junior"

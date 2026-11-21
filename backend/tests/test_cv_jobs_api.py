"""Offline integration-style API tests for /api/v1/cv/analyze and /api/v1/jobs/{job_id}."""

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
from app.core.config import settings
from app.core.exceptions import AppException
from app.core.security import get_jwks_client
from app.main import app
from app.schemas.profile import (
    CandidateProfileExtracted,
    EnrichmentSummary,
    ExternalLinks,
    SkillItem,
)
from app.services.analysis_pipeline_service import run_analysis_pipeline
from app.services.enrichment.enrichment_service import EnrichedProfileResult

TEST_SUPABASE_URL = "https://test-project.supabase.co"


class FakeJobRepository:
    """In-memory JobRepository recording status transitions."""

    def __init__(self) -> None:
        self.jobs: dict[str, dict[str, Any]] = {}
        self.status_history: list[str] = []

    async def create_job(
        self,
        user_id: str,
        profile_id: str | None = None,
    ) -> dict[str, Any]:
        job_id = str(uuid4())
        now = datetime.now(tz=UTC).isoformat()
        record = {
            "id": job_id,
            "user_id": user_id,
            "profile_id": profile_id,
            "status": "PENDING",
            "progress_percent": 0,
            "current_step_message": "Queued",
            "error_code": None,
            "error_message": None,
            "created_at": now,
            "updated_at": now,
        }
        self.jobs[job_id] = record
        return dict(record)

    async def get_job_by_id(self, job_id: str, user_id: str) -> dict[str, Any] | None:
        job = self.jobs.get(job_id)
        if job and job["user_id"] == user_id:
            return dict(job)
        return None

    async def update_job_status(
        self,
        job_id: str,
        status: str,
        progress_percent: int,
        current_step_message: str | None = None,
        profile_id: str | None = None,
        error_code: str | None = None,
        error_message: str | None = None,
    ) -> dict[str, Any] | None:
        job = self.jobs.get(job_id)
        if job is None:
            return None
        self.status_history.append(status)
        job["status"] = status
        job["progress_percent"] = progress_percent
        if current_step_message is not None:
            job["current_step_message"] = current_step_message
        if profile_id is not None:
            job["profile_id"] = profile_id
        if error_code is not None:
            job["error_code"] = error_code
        if error_message is not None:
            job["error_message"] = error_message
        job["updated_at"] = datetime.now(tz=UTC).isoformat()
        return dict(job)


class FakeProfileRepository:
    """In-memory ProfileRepository capturing created candidate profiles."""

    def __init__(self) -> None:
        self.profiles: dict[str, dict[str, Any]] = {}

    async def create_profile(
        self,
        user_id: str,
        data: dict[str, Any],
    ) -> dict[str, Any]:
        profile_id = str(uuid4())
        record = {"id": profile_id, "user_id": user_id, **data}
        self.profiles[profile_id] = record
        return dict(record)


def _sign_token(private_key: ec.EllipticCurvePrivateKey, user_id: str = "user-123") -> str:
    now = datetime.now(tz=UTC)
    return jwt.encode(
        {
            "sub": user_id,
            "email": f"{user_id}@example.com",
            "aud": "authenticated",
            "iss": f"{TEST_SUPABASE_URL}/auth/v1",
            "iat": int(now.timestamp()),
            "exp": int((now + timedelta(minutes=15)).timestamp()),
            "role": "authenticated",
        },
        private_key,
        algorithm="ES256",
        headers={"kid": "test-kid"},
    )


ApiContext = tuple[
    TestClient,
    FakeJobRepository,
    FakeProfileRepository,
    ec.EllipticCurvePrivateKey,
]


@pytest.fixture()
def api_context(
    monkeypatch: pytest.MonkeyPatch,
) -> Iterator[ApiContext]:
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_key = private_key.public_key()

    get_jwks_client.cache_clear()
    monkeypatch.setattr(settings, "SUPABASE_URL", TEST_SUPABASE_URL)
    fake_jwks = SimpleNamespace(
        get_signing_key_from_jwt=lambda _token: SimpleNamespace(key=public_key),
    )
    monkeypatch.setattr("app.core.security.get_jwks_client", lambda: fake_jwks)

    fake_jobs = FakeJobRepository()
    fake_profiles = FakeProfileRepository()
    app.dependency_overrides[get_job_repository] = lambda: fake_jobs
    app.dependency_overrides[get_profile_repository] = lambda: fake_profiles

    try:
        with TestClient(app) as client:
            yield client, fake_jobs, fake_profiles, private_key
    finally:
        app.dependency_overrides.clear()
        get_jwks_client.cache_clear()


def test_analyze_cv_valid_pdf_returns_202_and_completes_background_job(
    api_context: tuple[
        TestClient, FakeJobRepository, FakeProfileRepository, ec.EllipticCurvePrivateKey
    ],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client, fake_jobs, fake_profiles, private_key = api_context
    token = _sign_token(private_key, "user-abc")

    async def fake_upload(**kwargs: Any) -> str:
        return f"cvs/{kwargs['user_id']}/{kwargs['job_id']}_cv.pdf"

    extracted = CandidateProfileExtracted(
        full_name="Nguyen Van A",
        headline="Android Developer",
        years_of_experience=1.0,
        skills=[
            SkillItem(
                name="Kotlin",
                category="language",
                level="advanced",
                verified_by=["cv"],
            )
        ],
        external_links=ExternalLinks(github="https://github.com/example-dev"),
        profile_summary="Kotlin Android developer.",
    )

    async def fake_parse(*_args: Any, **_kwargs: Any) -> CandidateProfileExtracted:
        return extracted

    async def fake_enrich(*_args: Any, **_kwargs: Any) -> EnrichedProfileResult:
        return EnrichedProfileResult(
            profile=extracted,
            enrichment_summary=EnrichmentSummary(source_statuses={"github": "success"}),
        )

    async def fake_embed(*_args: Any, **_kwargs: Any) -> list[float]:
        return [0.1] * 768

    monkeypatch.setattr("app.api.v1.cv.upload_cv_to_storage", fake_upload)
    monkeypatch.setattr("app.services.analysis_pipeline_service.parse_cv_document", fake_parse)
    monkeypatch.setattr("app.services.analysis_pipeline_service.enrich_profile", fake_enrich)
    monkeypatch.setattr("app.services.analysis_pipeline_service.generate_embedding", fake_embed)

    response = client.post(
        "/api/v1/cv/analyze",
        headers={"Authorization": f"Bearer {token}"},
        files={"cv_file": ("resume.pdf", b"%PDF-1.4\n%fake-pdf-body", "application/pdf")},
    )

    assert response.status_code == 202
    body = response.json()
    assert body["success"] is True
    assert body["data"]["status"] == "PENDING"
    job_id = body["data"]["id"]

    # BackgroundTasks executed synchronously by TestClient; poll job status now.
    poll_resp = client.get(
        f"/api/v1/jobs/{job_id}",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert poll_resp.status_code == 200
    poll_data = poll_resp.json()["data"]
    assert poll_data["status"] == "COMPLETED"
    assert poll_data["progress_percent"] == 100
    assert poll_data["profile_id"] in fake_profiles.profiles
    assert fake_jobs.status_history == [
        "EXTRACTING",
        "ENRICHING",
        "ENRICHING",
        "MATCHING",
        "COMPLETED",
    ]


def test_analyze_cv_rejects_spoofed_format_and_missing_auth(
    api_context: tuple[
        TestClient, FakeJobRepository, FakeProfileRepository, ec.EllipticCurvePrivateKey
    ],
) -> None:
    client, fake_jobs, _, private_key = api_context

    unauth_resp = client.post(
        "/api/v1/cv/analyze",
        files={"cv_file": ("resume.pdf", b"%PDF-1.4\nbody", "application/pdf")},
    )
    assert unauth_resp.status_code == 401
    assert unauth_resp.json()["error"]["code"] == "AUTH_MISSING_TOKEN"

    token = _sign_token(private_key, "user-abc")
    bad_file_resp = client.post(
        "/api/v1/cv/analyze",
        headers={"Authorization": f"Bearer {token}"},
        files={"cv_file": ("fake.pdf", b"plain text not pdf", "application/pdf")},
    )
    assert bad_file_resp.status_code == 400
    assert bad_file_resp.json()["error"]["code"] == "CV_UNSUPPORTED_FORMAT"
    assert fake_jobs.jobs == {}


def test_get_job_returns_404_for_invalid_uuid_or_other_user(
    api_context: tuple[
        TestClient, FakeJobRepository, FakeProfileRepository, ec.EllipticCurvePrivateKey
    ],
) -> None:
    client, fake_jobs, _, private_key = api_context
    job_id = str(uuid4())
    fake_jobs.jobs[job_id] = {
        "id": job_id,
        "user_id": "owner-user",
        "profile_id": None,
        "status": "PENDING",
        "progress_percent": 0,
        "current_step_message": None,
        "error_code": None,
        "error_message": None,
        "created_at": None,
        "updated_at": None,
    }

    other_token = _sign_token(private_key, "attacker-user")
    idor_resp = client.get(
        f"/api/v1/jobs/{job_id}",
        headers={"Authorization": f"Bearer {other_token}"},
    )
    assert idor_resp.status_code == 404
    assert idor_resp.json()["error"]["code"] == "JOB_NOT_FOUND"

    bad_uuid_resp = client.get(
        "/api/v1/jobs/not-a-valid-uuid",
        headers={"Authorization": f"Bearer {other_token}"},
    )
    assert bad_uuid_resp.status_code == 404
    assert bad_uuid_resp.json()["error"]["code"] == "JOB_NOT_FOUND"


@pytest.mark.asyncio
async def test_pipeline_marks_job_failed_with_error_code_on_exception(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake_jobs = FakeJobRepository()
    fake_profiles = FakeProfileRepository()
    job = await fake_jobs.create_job("user-1")
    job_id = job["id"]

    async def failing_parse(*_args: Any, **_kwargs: Any) -> CandidateProfileExtracted:
        raise AppException(422, "CV_PARSE_FAILED", "CV is unreadable.")

    monkeypatch.setattr("app.services.analysis_pipeline_service.parse_cv_document", failing_parse)

    await run_analysis_pipeline(
        job_id=job_id,
        user_id="user-1",
        cv_bytes=b"%PDF-1.4\nbody",
        cv_content_type="application/pdf",
        job_repo=fake_jobs,  # type: ignore[arg-type]
        profile_repo=fake_profiles,  # type: ignore[arg-type]
    )

    updated = fake_jobs.jobs[job_id]
    assert updated["status"] == "FAILED"
    assert updated["error_code"] == "CV_PARSE_FAILED"
    assert updated["error_message"] == "CV is unreadable."

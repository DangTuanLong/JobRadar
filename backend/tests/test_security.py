"""Unit tests for Supabase JWKS authentication in app.core.security."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import settings
from app.core.exceptions import register_exception_handlers
from app.core.security import CurrentUser, get_jwks_client
from app.schemas.response import ApiResponse

TEST_SUPABASE_URL = "https://test-project.supabase.co"
TEST_ISSUER = f"{TEST_SUPABASE_URL}/auth/v1"
TEST_AUDIENCE = "authenticated"
TEST_KEY_ID = "test-es256-kid"


def _build_security_test_app() -> FastAPI:
    test_app = FastAPI()
    register_exception_handlers(test_app)

    @test_app.get("/protected", response_model=ApiResponse[dict[str, str | None]])
    async def protected_endpoint(
        current_user: CurrentUser,
    ) -> ApiResponse[dict[str, str | None]]:
        return ApiResponse(
            success=True,
            data={
                "user_id": current_user.user_id,
                "email": current_user.email,
                "role": current_user.role,
            },
            error=None,
        )

    return test_app


@pytest.fixture()
def ec_key_pair() -> tuple[ec.EllipticCurvePrivateKey, ec.EllipticCurvePublicKey]:
    private_key = ec.generate_private_key(ec.SECP256R1())
    return private_key, private_key.public_key()


@pytest.fixture()
def auth_client(
    monkeypatch: pytest.MonkeyPatch,
    ec_key_pair: tuple[ec.EllipticCurvePrivateKey, ec.EllipticCurvePublicKey],
) -> Iterator[TestClient]:
    _, public_key = ec_key_pair
    get_jwks_client.cache_clear()
    monkeypatch.setattr(settings, "SUPABASE_URL", TEST_SUPABASE_URL)

    fake_jwks_client = SimpleNamespace(
        get_signing_key_from_jwt=lambda _token: SimpleNamespace(key=public_key),
    )
    monkeypatch.setattr("app.core.security.get_jwks_client", lambda: fake_jwks_client)

    with TestClient(_build_security_test_app()) as client:
        yield client

    get_jwks_client.cache_clear()


def _sign_es256_token(
    private_key: ec.EllipticCurvePrivateKey,
    *,
    sub: str | None = "user-123",
    email: str | None = "candidate@example.com",
    audience: str = TEST_AUDIENCE,
    issuer: str = TEST_ISSUER,
    expires_delta: timedelta = timedelta(minutes=15),
) -> str:
    now = datetime.now(tz=UTC)
    payload: dict[str, Any] = {
        "aud": audience,
        "iss": issuer,
        "iat": int(now.timestamp()),
        "exp": int((now + expires_delta).timestamp()),
        "role": "authenticated",
    }
    if sub is not None:
        payload["sub"] = sub
    if email is not None:
        payload["email"] = email
    return jwt.encode(
        payload,
        private_key,
        algorithm="ES256",
        headers={"kid": TEST_KEY_ID},
    )


def test_valid_es256_token_returns_authenticated_user(
    auth_client: TestClient,
    ec_key_pair: tuple[ec.EllipticCurvePrivateKey, ec.EllipticCurvePublicKey],
) -> None:
    private_key, _ = ec_key_pair
    token = _sign_es256_token(private_key)

    response = auth_client.get(
        "/protected",
        headers={"Authorization": f"Bearer {token}"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["error"] is None
    assert body["data"]["user_id"] == "user-123"
    assert body["data"]["email"] == "candidate@example.com"
    assert body["data"]["role"] == "authenticated"


def test_missing_authorization_header_returns_401(auth_client: TestClient) -> None:
    response = auth_client.get("/protected")

    assert response.status_code == 401
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "AUTH_MISSING_TOKEN"


def test_expired_token_returns_401(
    auth_client: TestClient,
    ec_key_pair: tuple[ec.EllipticCurvePrivateKey, ec.EllipticCurvePublicKey],
) -> None:
    private_key, _ = ec_key_pair
    expired_token = _sign_es256_token(private_key, expires_delta=timedelta(minutes=-5))

    response = auth_client.get(
        "/protected",
        headers={"Authorization": f"Bearer {expired_token}"},
    )

    assert response.status_code == 401
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "AUTH_INVALID_TOKEN"


def test_wrong_signature_token_returns_401(auth_client: TestClient) -> None:
    other_private_key = ec.generate_private_key(ec.SECP256R1())
    forged_token = _sign_es256_token(other_private_key)

    response = auth_client.get(
        "/protected",
        headers={"Authorization": f"Bearer {forged_token}"},
    )

    assert response.status_code == 401
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "AUTH_INVALID_TOKEN"


def test_wrong_audience_token_returns_401(
    auth_client: TestClient,
    ec_key_pair: tuple[ec.EllipticCurvePrivateKey, ec.EllipticCurvePublicKey],
) -> None:
    private_key, _ = ec_key_pair
    wrong_aud_token = _sign_es256_token(private_key, audience="anon")

    response = auth_client.get(
        "/protected",
        headers={"Authorization": f"Bearer {wrong_aud_token}"},
    )

    assert response.status_code == 401
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "AUTH_INVALID_TOKEN"


def test_missing_sub_claim_returns_401(
    auth_client: TestClient,
    ec_key_pair: tuple[ec.EllipticCurvePrivateKey, ec.EllipticCurvePublicKey],
) -> None:
    private_key, _ = ec_key_pair
    token_without_sub = _sign_es256_token(private_key, sub=None)

    response = auth_client.get(
        "/protected",
        headers={"Authorization": f"Bearer {token_without_sub}"},
    )

    assert response.status_code == 401
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "AUTH_INVALID_PAYLOAD"

"""Supabase JWT verification via asymmetric JWKS endpoint."""

from __future__ import annotations

import asyncio
from functools import lru_cache
from typing import Annotated, Any

import jwt
from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt import InvalidTokenError, PyJWKClient
from pydantic import BaseModel

from app.core.config import settings
from app.core.exceptions import AppException
from app.core.logging import get_logger

logger = get_logger(__name__)

ALLOWED_JWT_ALGORITHMS = ["ES256", "RS256"]
JWKS_CACHE_LIFESPAN_SECONDS = 3600
EXPECTED_JWT_AUDIENCE = "authenticated"

bearer_scheme = HTTPBearer(auto_error=False)


class AuthenticatedUser(BaseModel):
    """Authenticated user claims extracted from a verified Supabase JWT."""

    user_id: str
    email: str | None = None
    role: str = "authenticated"


@lru_cache(maxsize=1)
def get_jwks_client() -> PyJWKClient:
    """Create and cache a PyJWKClient pointing to Supabase Auth JWKS."""
    if not settings.SUPABASE_URL:
        logger.error("Authentication configuration error: SUPABASE_URL is empty.")
        raise AppException(
            status_code=500,
            code="AUTH_CONFIG_ERROR",
            message="Supabase URL is not configured.",
        )
    jwks_url = f"{settings.SUPABASE_URL.rstrip('/')}/auth/v1/.well-known/jwks.json"
    return PyJWKClient(
        jwks_url,
        cache_keys=True,
        lifespan=JWKS_CACHE_LIFESPAN_SECONDS,
    )


def _decode_jwt_sync(token: str) -> dict[str, Any]:
    """Synchronously fetch the JWKS signing key and verify the JWT."""
    jwks_client = get_jwks_client()
    signing_key = jwks_client.get_signing_key_from_jwt(token)
    issuer = f"{settings.SUPABASE_URL.rstrip('/')}/auth/v1"
    return jwt.decode(
        token,
        signing_key.key,
        algorithms=ALLOWED_JWT_ALGORITHMS,
        audience=EXPECTED_JWT_AUDIENCE,
        issuer=issuer,
    )


async def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
) -> AuthenticatedUser:
    """FastAPI dependency validating Bearer JWT tokens against Supabase JWKS."""
    if credentials is None or not credentials.credentials:
        raise AppException(
            status_code=401,
            code="AUTH_MISSING_TOKEN",
            message="Missing or invalid Authorization header.",
        )
    try:
        payload = await asyncio.to_thread(_decode_jwt_sync, credentials.credentials)
    except InvalidTokenError as exc:
        logger.warning("JWT validation failed: %s", exc)
        raise AppException(
            status_code=401,
            code="AUTH_INVALID_TOKEN",
            message="Token is invalid or expired.",
        ) from exc
    except AppException:
        raise
    except Exception as exc:
        logger.warning("JWT verification failed unexpectedly: %s", exc)
        raise AppException(
            status_code=401,
            code="AUTH_VERIFICATION_FAILED",
            message="Could not verify authentication token.",
        ) from exc

    sub = payload.get("sub")
    if not sub:
        logger.warning("JWT payload rejected: missing 'sub' claim.")
        raise AppException(
            status_code=401,
            code="AUTH_INVALID_PAYLOAD",
            message="Token payload missing subject.",
        )
    return AuthenticatedUser(
        user_id=str(sub),
        email=payload.get("email"),
        role=str(payload.get("role", "authenticated")),
    )


CurrentUser = Annotated[AuthenticatedUser, Depends(get_current_user)]

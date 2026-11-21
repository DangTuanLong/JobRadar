"""Lazy singleton Supabase client, async query wrapper, and CV storage helper."""

from __future__ import annotations

import asyncio
import re
from collections.abc import Callable
from functools import lru_cache
from pathlib import PurePosixPath
from typing import TypeVar

from postgrest.exceptions import APIError
from supabase import Client, create_client

from app.core.config import settings
from app.core.constants import SUPABASE_CV_BUCKET
from app.core.exceptions import AppException
from app.core.logging import get_logger

logger = get_logger(__name__)

T = TypeVar("T")

_SAFE_FILENAME_RE = re.compile(r"[^A-Za-z0-9._-]+")
_MAX_FILENAME_LENGTH = 80


@lru_cache(maxsize=1)
def get_supabase_client() -> Client:
    """Lazily initialize and cache the Supabase client when first needed."""
    if not settings.SUPABASE_URL or not settings.SUPABASE_SECRET_KEY:
        logger.error(
            "Database configuration missing: SUPABASE_URL or SUPABASE_SECRET_KEY is not set."
        )
        raise AppException(
            status_code=500,
            code="DB_CONFIG_ERROR",
            message="Supabase database configuration is missing.",
        )
    try:
        return create_client(settings.SUPABASE_URL, settings.SUPABASE_SECRET_KEY)
    except Exception as exc:
        logger.exception("Failed to initialize Supabase client.", exc_info=exc)
        raise AppException(
            status_code=500,
            code="DB_CONNECTION_ERROR",
            message="Could not initialize database client.",
        ) from exc


async def execute_supabase_query(operation: str, query_fn: Callable[[], T]) -> T:
    """Run a synchronous Supabase SDK call in a worker thread with structured error mapping."""
    try:
        return await asyncio.to_thread(query_fn)
    except AppException:
        raise
    except APIError as exc:
        logger.error("Supabase API error during %s: %s", operation, exc)
        raise AppException(
            status_code=500,
            code="DB_QUERY_ERROR",
            message=f"Database error during {operation}.",
        ) from exc
    except Exception as exc:
        logger.exception("Unexpected database error during %s", operation, exc_info=exc)
        raise AppException(
            status_code=500,
            code="DB_QUERY_ERROR",
            message=f"Database error during {operation}.",
        ) from exc


def sanitize_filename(filename: str | None) -> str:
    """Sanitize a user-supplied filename for safe object storage paths."""
    raw_name = PurePosixPath((filename or "").replace("\\", "/")).name
    cleaned = _SAFE_FILENAME_RE.sub("_", raw_name).strip("._")
    if not cleaned:
        return "cv_upload.bin"
    if len(cleaned) > _MAX_FILENAME_LENGTH:
        stem = PurePosixPath(cleaned).stem[:64]
        suffix = PurePosixPath(cleaned).suffix[:16]
        return f"{stem}{suffix}"
    return cleaned


async def upload_cv_to_storage(
    *,
    user_id: str,
    job_id: str,
    filename: str | None,
    file_bytes: bytes,
    content_type: str,
    client: Client | None = None,
) -> str:
    """Upload a raw CV file to Supabase Storage bucket 'cvs' under {user_id}/{job_id}_{name}."""
    safe_name = sanitize_filename(filename)
    storage_path = f"{user_id}/{job_id}_{safe_name}"
    supabase_client = client if client is not None else get_supabase_client()

    await execute_supabase_query(
        "upload_cv_to_storage",
        lambda: supabase_client.storage.from_(SUPABASE_CV_BUCKET).upload(
            path=storage_path,
            file=file_bytes,
            file_options={"content-type": content_type, "upsert": "true"},
        ),
    )
    return f"{SUPABASE_CV_BUCKET}/{storage_path}"

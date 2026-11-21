"""Lazy singleton Supabase client and async query execution helper."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from functools import lru_cache
from typing import TypeVar

from postgrest.exceptions import APIError
from supabase import Client, create_client

from app.core.config import settings
from app.core.exceptions import AppException
from app.core.logging import get_logger

logger = get_logger(__name__)

T = TypeVar("T")


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

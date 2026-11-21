"""Lazy Google GenAI client and async retry helpers for Gemini API calls."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from functools import lru_cache
from typing import TypeVar

import httpx
from google import genai
from google.genai import errors as genai_errors

from app.core.config import settings
from app.core.constants import (
    GEMINI_MAX_RETRIES,
    GEMINI_RATE_LIMIT_BASE_DELAY_SECONDS,
    GEMINI_RETRY_BASE_DELAY_SECONDS,
)
from app.core.exceptions import AppException
from app.core.logging import get_logger

logger = get_logger(__name__)

T = TypeVar("T")

_RETRYABLE_STATUS_CODES = frozenset({408, 429, 500, 502, 503, 504})
_RETRYABLE_STATUS_TEXTS = ("RESOURCE_EXHAUSTED", "UNAVAILABLE", "DEADLINE_EXCEEDED")


@lru_cache(maxsize=1)
def get_gemini_client() -> genai.Client:
    """Lazily create and cache the Google GenAI SDK client."""
    if not settings.GEMINI_API_KEY:
        logger.error("Gemini configuration missing: GEMINI_API_KEY is empty.")
        raise AppException(
            status_code=500,
            code="AI_CONFIG_ERROR",
            message="Gemini API key is not configured.",
        )
    return genai.Client(api_key=settings.GEMINI_API_KEY)


def _is_rate_limit_error(exc: Exception) -> bool:
    if isinstance(exc, genai_errors.APIError):
        status_str = str(exc.status or "").upper()
        if exc.code == 429 or "RESOURCE_EXHAUSTED" in status_str:
            return True
    err_upper = str(exc).upper()
    return "429" in err_upper or "RESOURCE_EXHAUSTED" in err_upper


def _is_retryable_error(exc: Exception) -> bool:
    if isinstance(exc, genai_errors.APIError):
        status_str = str(exc.status or "").upper()
        return exc.code in _RETRYABLE_STATUS_CODES or any(
            token in status_str for token in _RETRYABLE_STATUS_TEXTS
        )
    return isinstance(
        exc,
        (httpx.TimeoutException, httpx.NetworkError, TimeoutError, ConnectionError),
    )


async def call_gemini_with_retry(
    operation: str,
    call_fn: Callable[[], Awaitable[T]],
    *,
    max_retries: int = GEMINI_MAX_RETRIES,
    base_delay: float = GEMINI_RETRY_BASE_DELAY_SECONDS,
    rate_limit_base_delay: float = GEMINI_RATE_LIMIT_BASE_DELAY_SECONDS,
) -> T:
    """Execute an async Gemini API coroutine with exponential backoff on transient failures."""
    last_exc: Exception | None = None

    for attempt in range(max_retries):
        try:
            return await call_fn()
        except AppException:
            raise
        except Exception as exc:
            last_exc = exc
            if not _is_retryable_error(exc):
                logger.error("Non-retryable Gemini error during %s: %s", operation, exc)
                raise AppException(
                    status_code=502,
                    code="AI_SERVICE_ERROR",
                    message=f"Gemini API error during {operation}.",
                ) from exc

            if attempt == max_retries - 1:
                break

            multiplier = 2**attempt
            if _is_rate_limit_error(exc):
                delay = rate_limit_base_delay * multiplier
            else:
                delay = base_delay * multiplier

            logger.warning(
                "Transient Gemini error during %s (attempt %d/%d): %s. Retrying in %.1fs.",
                operation,
                attempt + 1,
                max_retries,
                exc,
                delay,
            )
            await asyncio.sleep(delay)

    if last_exc is not None and _is_rate_limit_error(last_exc):
        logger.error("Gemini rate limit exhausted during %s: %s", operation, last_exc)
        raise AppException(
            status_code=429,
            code="AI_RATE_LIMITED",
            message="AI service rate limit exceeded. Please try again later.",
        ) from last_exc

    logger.error("Gemini service unavailable after %d attempts during %s.", max_retries, operation)
    raise AppException(
        status_code=502,
        code="AI_SERVICE_UNAVAILABLE",
        message=f"AI service unavailable during {operation}.",
    ) from last_exc

"""Lazy Google GenAI client, thinking config builder, and SLA fallback helpers."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from functools import lru_cache
from typing import TypeVar

import httpx
from google import genai
from google.genai import errors as genai_errors
from google.genai import types

from app.core.config import settings
from app.core.constants import (
    GEMINI_ALLOWED_THINKING_LEVELS,
    GEMINI_FALLBACK_MAX_ATTEMPTS,
    GEMINI_MAX_RETRIES,
    GEMINI_PRIMARY_MAX_ATTEMPTS,
    GEMINI_RATE_LIMIT_BASE_DELAY_SECONDS,
    GEMINI_RETRY_BASE_DELAY_SECONDS,
    GEMINI_SHORT_RETRY_DELAY_SECONDS,
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


def build_thinking_config(
    model_name: str,
    level: str | None,
) -> types.ThinkingConfig | None:
    """Build ThinkingConfig only for gemini-4* models with valid thinking_level."""
    clean_model = (model_name or "").strip().lower()
    clean_level = (level or "").strip().lower()
    if not clean_model.startswith("gemini-4") or not clean_level:
        return None
    if clean_level not in GEMINI_ALLOWED_THINKING_LEVELS:
        logger.warning(
            "Ignoring unsupported thinking_level='%s' for model='%s'.",
            clean_level,
            model_name,
        )
        return None
    return types.ThinkingConfig(thinking_level=clean_level)


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


def _raise_terminal_gemini_error(operation_name: str, last_exc: Exception | None) -> None:
    if last_exc is not None and _is_rate_limit_error(last_exc):
        logger.error("Gemini rate limit exhausted during %s: %s", operation_name, last_exc)
        raise AppException(
            status_code=429,
            code="AI_RATE_LIMITED",
            message="AI service rate limit exceeded. Please try again later.",
        ) from last_exc

    logger.error("Gemini service unavailable during %s: %s", operation_name, last_exc)
    raise AppException(
        status_code=502,
        code="AI_SERVICE_UNAVAILABLE",
        message=f"AI service unavailable during {operation_name}.",
    ) from last_exc


async def call_gemini_with_fallback(
    operation_name: str,
    call_fn: Callable[[str], Awaitable[T]],
    primary_model: str,
    fallback_model: str | None = None,
    timeout_seconds: float = 45.0,
    *,
    retry_delay_seconds: float = GEMINI_SHORT_RETRY_DELAY_SECONDS,
) -> T:
    """Call Gemini with hard per-attempt timeout, 1 short retry on primary, and model fallback."""
    last_exc: Exception | None = None
    clean_primary = primary_model.strip()

    for attempt in range(GEMINI_PRIMARY_MAX_ATTEMPTS):
        try:
            result = await asyncio.wait_for(
                call_fn(clean_primary),
                timeout=timeout_seconds,
            )
            logger.info(
                "Gemini %s succeeded on primary model='%s' (attempt %d/%d).",
                operation_name,
                clean_primary,
                attempt + 1,
                GEMINI_PRIMARY_MAX_ATTEMPTS,
            )
            return result
        except AppException:
            raise
        except TimeoutError as exc:
            last_exc = exc
            logger.warning(
                "Gemini %s timed out after %.1fs on primary model='%s'; switching to fallback.",
                operation_name,
                timeout_seconds,
                clean_primary,
            )
            break
        except Exception as exc:
            last_exc = exc
            if not _is_retryable_error(exc):
                logger.error(
                    "Non-retryable Gemini error on '%s' during %s: %s",
                    clean_primary,
                    operation_name,
                    exc,
                )
                raise AppException(
                    status_code=502,
                    code="AI_SERVICE_ERROR",
                    message=f"Gemini API error during {operation_name}.",
                ) from exc

            if attempt < GEMINI_PRIMARY_MAX_ATTEMPTS - 1:
                logger.warning(
                    "Transient error on primary model='%s' during %s (attempt %d/%d): %s. "
                    "Retrying in %.1fs.",
                    clean_primary,
                    operation_name,
                    attempt + 1,
                    GEMINI_PRIMARY_MAX_ATTEMPTS,
                    exc,
                    retry_delay_seconds,
                )
                await asyncio.sleep(retry_delay_seconds)
            else:
                logger.warning(
                    "Primary model='%s' exhausted %d attempts during %s: %s.",
                    clean_primary,
                    GEMINI_PRIMARY_MAX_ATTEMPTS,
                    operation_name,
                    exc,
                )

    clean_fallback = (fallback_model or "").strip()
    if clean_fallback and clean_fallback != clean_primary:
        logger.warning(
            "Falling back from '%s' to '%s' for %s.",
            clean_primary,
            clean_fallback,
            operation_name,
        )
        for fb_attempt in range(GEMINI_FALLBACK_MAX_ATTEMPTS):
            try:
                result = await asyncio.wait_for(
                    call_fn(clean_fallback),
                    timeout=timeout_seconds,
                )
                logger.info(
                    "Gemini %s succeeded on fallback model='%s' (attempt %d/%d).",
                    operation_name,
                    clean_fallback,
                    fb_attempt + 1,
                    GEMINI_FALLBACK_MAX_ATTEMPTS,
                )
                return result
            except AppException:
                raise
            except Exception as exc:
                last_exc = exc
                if not _is_retryable_error(exc):
                    logger.error(
                        "Non-retryable Gemini error on fallback '%s' during %s: %s",
                        clean_fallback,
                        operation_name,
                        exc,
                    )
                    raise AppException(
                        status_code=502,
                        code="AI_SERVICE_ERROR",
                        message=f"Gemini API error during {operation_name}.",
                    ) from exc

                if fb_attempt < GEMINI_FALLBACK_MAX_ATTEMPTS - 1:
                    logger.warning(
                        "Transient error on fallback model='%s' during %s (attempt %d/%d): %s. "
                        "Retrying in %.1fs.",
                        clean_fallback,
                        operation_name,
                        fb_attempt + 1,
                        GEMINI_FALLBACK_MAX_ATTEMPTS,
                        exc,
                        retry_delay_seconds,
                    )
                    await asyncio.sleep(retry_delay_seconds)

    _raise_terminal_gemini_error(operation_name, last_exc)
    raise AssertionError("Unreachable")


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
            delay = (
                rate_limit_base_delay * multiplier
                if _is_rate_limit_error(exc)
                else base_delay * multiplier
            )
            logger.warning(
                "Transient Gemini error during %s (attempt %d/%d): %s. Retrying in %.1fs.",
                operation,
                attempt + 1,
                max_retries,
                exc,
                delay,
            )
            await asyncio.sleep(delay)

    _raise_terminal_gemini_error(operation, last_exc)
    raise AssertionError("Unreachable")

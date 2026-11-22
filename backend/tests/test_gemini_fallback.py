"""Offline unit tests for Gemini SLA timeout, thinking_level builder, and model fallback."""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock

import pytest
from google.genai import errors as genai_errors
from google.genai import types

from app.core.exceptions import AppException
from app.services.gemini_client import build_thinking_config, call_gemini_with_fallback


def test_build_thinking_config_only_enables_for_gemini_4_valid_levels() -> None:
    cfg_low = build_thinking_config("gemini-4-hydrogen", "low")
    assert cfg_low is not None and cfg_low.thinking_level == types.ThinkingLevel.LOW

    cfg_med = build_thinking_config("gemini-4-hydrogen", "MEDIUM")
    assert cfg_med is not None and cfg_med.thinking_level == types.ThinkingLevel.MEDIUM

    # Rejected on gemini-4-hydrogen -> must return None rather than sending "minimal".
    assert build_thinking_config("gemini-4-hydrogen", "minimal") is None
    # Non-gemini-4 models must return None for safe fallback compatibility.
    assert build_thinking_config("gemini-3.5-flash-lite", "low") is None
    assert build_thinking_config("gemini-2.5-flash", "low") is None


@pytest.mark.asyncio
async def test_primary_success_on_first_try_does_not_call_fallback() -> None:
    called_models: list[str] = []

    async def call_fn(model: str) -> str:
        called_models.append(model)
        return f"ok:{model}"

    result = await call_gemini_with_fallback(
        "test_op",
        call_fn,
        primary_model="gemini-4-hydrogen",
        fallback_model="gemini-3.5-flash-lite",
        timeout_seconds=1.0,
    )
    assert result == "ok:gemini-4-hydrogen"
    assert called_models == ["gemini-4-hydrogen"]


@pytest.mark.asyncio
async def test_primary_timeout_immediately_switches_to_fallback() -> None:
    called_models: list[str] = []

    async def call_fn(model: str) -> str:
        called_models.append(model)
        if model == "gemini-4-hydrogen":
            await asyncio.sleep(0.5)
        return f"fallback_ok:{model}"

    result = await call_gemini_with_fallback(
        "test_timeout",
        call_fn,
        primary_model="gemini-4-hydrogen",
        fallback_model="gemini-3.5-flash-lite",
        timeout_seconds=0.05,
        retry_delay_seconds=0.01,
    )
    assert result == "fallback_ok:gemini-3.5-flash-lite"
    assert called_models == ["gemini-4-hydrogen", "gemini-3.5-flash-lite"]


@pytest.mark.asyncio
async def test_primary_503_twice_retries_once_then_falls_back(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sleep_mock = AsyncMock()
    monkeypatch.setattr("app.services.gemini_client.asyncio.sleep", sleep_mock)

    called_models: list[str] = []
    err_503 = genai_errors.APIError(
        503,
        {"error": {"code": 503, "message": "High demand", "status": "UNAVAILABLE"}},
    )

    async def call_fn(model: str) -> str:
        called_models.append(model)
        if model == "gemini-4-hydrogen":
            raise err_503
        return "recovered_on_lite"

    result = await call_gemini_with_fallback(
        "test_503",
        call_fn,
        primary_model="gemini-4-hydrogen",
        fallback_model="gemini-3.5-flash-lite",
        timeout_seconds=5.0,
    )
    assert result == "recovered_on_lite"
    assert called_models == [
        "gemini-4-hydrogen",
        "gemini-4-hydrogen",
        "gemini-3.5-flash-lite",
    ]
    assert sleep_mock.await_count == 1


@pytest.mark.asyncio
async def test_both_primary_and_fallback_failing_raises_app_exception(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("app.services.gemini_client.asyncio.sleep", AsyncMock())
    err_503 = genai_errors.APIError(
        503,
        {"error": {"code": 503, "message": "Overloaded", "status": "UNAVAILABLE"}},
    )
    called_models: list[str] = []

    async def call_fn(model: str) -> str:
        called_models.append(model)
        raise err_503

    with pytest.raises(AppException) as exc_info:
        await call_gemini_with_fallback(
            "test_all_fail",
            call_fn,
            primary_model="gemini-4-hydrogen",
            fallback_model="gemini-3.5-flash-lite",
            timeout_seconds=1.0,
        )

    assert exc_info.value.status_code == 502
    assert exc_info.value.code == "AI_SERVICE_UNAVAILABLE"
    assert called_models == [
        "gemini-4-hydrogen",
        "gemini-4-hydrogen",
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash-lite",
    ]


@pytest.mark.asyncio
async def test_non_retryable_400_error_does_not_trigger_fallback() -> None:
    called_models: list[str] = []
    err_400 = genai_errors.APIError(
        400,
        {"error": {"code": 400, "message": "Invalid schema", "status": "INVALID_ARGUMENT"}},
    )

    async def call_fn(model: str) -> str:
        called_models.append(model)
        raise err_400

    with pytest.raises(AppException) as exc_info:
        await call_gemini_with_fallback(
            "test_400",
            call_fn,
            primary_model="gemini-4-hydrogen",
            fallback_model="gemini-3.5-flash-lite",
            timeout_seconds=1.0,
        )

    assert exc_info.value.status_code == 502
    assert exc_info.value.code == "AI_SERVICE_ERROR"
    assert called_models == ["gemini-4-hydrogen"]

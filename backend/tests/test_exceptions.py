"""Unit tests for global exception handlers and Standard Envelope error formatting."""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel, Field, field_validator

from app.core.exceptions import AppException, register_exception_handlers


class _SamplePayload(BaseModel):
    name: str
    age: int = Field(gt=0)

    @field_validator("name")
    @classmethod
    def validate_non_blank_name(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("name must not be blank")
        return value


def _build_exception_test_app() -> FastAPI:
    test_app = FastAPI()
    register_exception_handlers(test_app)

    @test_app.post("/validate")
    async def validate_endpoint(payload: _SamplePayload) -> dict[str, str]:
        return {"name": payload.name}

    @test_app.get("/app-error")
    async def app_error_endpoint() -> None:
        raise AppException(
            status_code=403,
            code="FORBIDDEN_RESOURCE",
            message="You do not have permission to access this resource.",
            details={"resource": "analysis_job"},
        )

    @test_app.get("/unhandled-error")
    async def unhandled_error_endpoint() -> None:
        raise RuntimeError("INTERNAL_SECRET_DATABASE_FAILURE_TRACE")

    return test_app


def test_not_found_returns_standard_envelope() -> None:
    client = TestClient(_build_exception_test_app())
    response = client.get("/does-not-exist")

    assert response.status_code == 404
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "HTTP_404"
    assert isinstance(body["error"]["message"], str)


def test_validation_error_returns_422_standard_envelope() -> None:
    client = TestClient(_build_exception_test_app())
    response = client.post("/validate", json={"name": "   ", "age": -1})

    assert response.status_code == 422
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert isinstance(body["error"]["details"], list)
    assert len(body["error"]["details"]) == 2


def test_custom_app_exception_returns_standard_envelope() -> None:
    client = TestClient(_build_exception_test_app())
    response = client.get("/app-error")

    assert response.status_code == 403
    body = response.json()
    assert body == {
        "success": False,
        "data": None,
        "error": {
            "code": "FORBIDDEN_RESOURCE",
            "message": "You do not have permission to access this resource.",
            "details": {"resource": "analysis_job"},
        },
    }


def test_unhandled_exception_returns_500_without_traceback_leak() -> None:
    client = TestClient(_build_exception_test_app(), raise_server_exceptions=False)
    response = client.get("/unhandled-error")

    assert response.status_code == 500
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "INTERNAL_SERVER_ERROR"
    assert body["error"]["details"] is None
    assert "INTERNAL_SECRET_DATABASE_FAILURE_TRACE" not in response.text
    assert "RuntimeError" not in response.text
    assert "Traceback" not in response.text

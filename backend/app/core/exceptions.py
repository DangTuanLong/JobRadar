"""Application exceptions and global FastAPI exception handlers."""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.logging import get_logger
from app.schemas.response import ApiResponse, ErrorDetail

logger = get_logger(__name__)

CODE_VALIDATION_ERROR = "VALIDATION_ERROR"
CODE_INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR"
DEFAULT_HTTP_ERROR_MESSAGE = "HTTP error occurred."
DEFAULT_VALIDATION_MESSAGE = "Request validation failed."
DEFAULT_INTERNAL_ERROR_MESSAGE = "An internal server error occurred."


class AppException(Exception):
    """Domain or infrastructure exception mapped to Standard Envelope responses."""

    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        details: Any = None,
    ) -> None:
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = details
        super().__init__(message)


def _build_error_response(
    status_code: int,
    code: str,
    message: str,
    details: Any = None,
) -> JSONResponse:
    payload = ApiResponse[None](
        success=False,
        data=None,
        error=ErrorDetail(
            code=code,
            message=message,
            details=details,
        ),
    )
    return JSONResponse(
        status_code=status_code,
        content=payload.model_dump(mode="json"),
    )


async def app_exception_handler(request: Request, exc: AppException) -> JSONResponse:
    """Handle known AppException errors with structured logging."""
    log_method = logger.error if exc.status_code >= 500 else logger.warning
    log_method(
        "AppException on %s %s [%d %s]: %s",
        request.method,
        request.url.path,
        exc.status_code,
        exc.code,
        exc.message,
    )
    return _build_error_response(
        status_code=exc.status_code,
        code=exc.code,
        message=exc.message,
        details=exc.details,
    )


async def validation_exception_handler(
    request: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    """Handle FastAPI request validation errors with condensed JSON-safe details."""
    condensed_errors = [
        {
            "field": ".".join(str(part) for part in err.get("loc", ())),
            "message": str(err.get("msg", "Invalid value.")),
            "type": str(err.get("type", "validation_error")),
        }
        for err in exc.errors()
    ]
    logger.warning(
        "Validation error on %s %s: %d issue(s)",
        request.method,
        request.url.path,
        len(condensed_errors),
    )
    return _build_error_response(
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        code=CODE_VALIDATION_ERROR,
        message=DEFAULT_VALIDATION_MESSAGE,
        details=condensed_errors,
    )


async def http_exception_handler(
    request: Request,
    exc: StarletteHTTPException,
) -> JSONResponse:
    """Handle Starlette and FastAPI HTTP exceptions in Standard Envelope format."""
    if isinstance(exc.detail, str) and exc.detail:
        message = exc.detail
        details = None
    elif exc.detail is None or exc.detail == "":
        message = DEFAULT_HTTP_ERROR_MESSAGE
        details = None
    else:
        message = DEFAULT_HTTP_ERROR_MESSAGE
        details = exc.detail

    logger.warning(
        "HTTP exception on %s %s [%d]: %s",
        request.method,
        request.url.path,
        exc.status_code,
        message,
    )
    return _build_error_response(
        status_code=exc.status_code,
        code=f"HTTP_{exc.status_code}",
        message=message,
        details=details,
    )


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Log unhandled exceptions internally and return a safe generic 500 envelope."""
    logger.exception(
        "Unhandled exception on %s %s",
        request.method,
        request.url.path,
        exc_info=exc,
    )
    return _build_error_response(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        code=CODE_INTERNAL_SERVER_ERROR,
        message=DEFAULT_INTERNAL_ERROR_MESSAGE,
        details=None,
    )


def register_exception_handlers(app: FastAPI) -> None:
    """Register all global exception handlers on the FastAPI application."""
    app.add_exception_handler(AppException, app_exception_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)

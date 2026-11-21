"""Structured application logging configuration with job_id trace context."""

from __future__ import annotations

import logging
import sys
from contextvars import ContextVar, Token

from app.core.config import settings

DEFAULT_JOB_ID = "-"
LOG_FORMAT = "%(asctime)s | %(levelname)-8s | %(name)s | job_id=%(job_id)s | %(message)s"
LOG_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

_job_id_context: ContextVar[str] = ContextVar("job_id", default=DEFAULT_JOB_ID)
_NOISY_LOGGERS = ("httpx", "httpcore", "hpack")


class JobContextFilter(logging.Filter):
    """Inject job_id into every log record so formatting always has trace context."""

    def filter(self, record: logging.LogRecord) -> bool:
        if not getattr(record, "job_id", None):
            record.job_id = _job_id_context.get()
        return True


def set_job_id(job_id: str | None) -> Token[str]:
    """Set the current job_id in contextvars for downstream logs."""
    return _job_id_context.set(job_id or DEFAULT_JOB_ID)


def reset_job_id(token: Token[str]) -> None:
    """Restore the previous job_id contextvars token."""
    _job_id_context.reset(token)


def get_job_id() -> str:
    """Return the active job_id from contextvars."""
    return _job_id_context.get()


def _resolve_log_level(level_name: str) -> int:
    resolved = getattr(logging, level_name.upper(), None)
    return resolved if isinstance(resolved, int) else logging.INFO


def setup_logging(level: str | None = None) -> None:
    """Configure root logging handler, formatter, and context filter once."""
    resolved_level = _resolve_log_level(level or settings.LOG_LEVEL)
    root_logger = logging.getLogger()
    root_logger.setLevel(resolved_level)

    # Clear existing handlers to avoid duplicate log lines on reload or repeated calls.
    for existing_handler in list(root_logger.handlers):
        root_logger.removeHandler(existing_handler)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(resolved_level)
    handler.setFormatter(logging.Formatter(fmt=LOG_FORMAT, datefmt=LOG_DATE_FORMAT))
    handler.addFilter(JobContextFilter())

    root_logger.addHandler(handler)

    for logger_name in _NOISY_LOGGERS:
        logging.getLogger(logger_name).setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    """Return a named logger instance."""
    return logging.getLogger(name)

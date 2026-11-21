"""JobRadar FastAPI application entrypoint."""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1.health import router as health_router
from app.core.exceptions import register_exception_handlers
from app.core.logging import get_logger, setup_logging

API_V1_PREFIX = "/api/v1"


def create_app() -> FastAPI:
    """Create and configure the FastAPI application instance."""
    setup_logging()
    logger = get_logger(__name__)

    application = FastAPI(
        title="JobRadar Backend API",
        version="1.0.0",
    )

    application.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    register_exception_handlers(application)
    application.include_router(health_router, prefix=API_V1_PREFIX, tags=["health"])

    logger.info("JobRadar Backend API initialized.")
    return application


app = create_app()

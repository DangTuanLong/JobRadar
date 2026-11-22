from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    APP_ENV: str = "development"
    LOG_LEVEL: str = "INFO"

    SUPABASE_URL: str = ""
    SUPABASE_PUBLISHABLE_KEY: str = ""
    SUPABASE_SECRET_KEY: str = ""

    GEMINI_API_KEY: str = ""
    GEMINI_MODEL_EXTRACTION: str = "gemini-4-hydrogen"
    GEMINI_MODEL_MATCHING: str = "gemini-4-hydrogen"
    GEMINI_MODEL_FALLBACK: str | None = "gemini-3.5-flash-lite"
    GEMINI_MODEL_EMBEDDING: str = "gemini-embedding-001"
    GEMINI_EXTRACTION_TIMEOUT_SECONDS: float = 45.0
    GEMINI_MATCHING_TIMEOUT_SECONDS: float = 60.0
    GEMINI_EXTRACTION_THINKING_LEVEL: str = "low"
    GEMINI_MATCHING_THINKING_LEVEL: str = "medium"
    GITHUB_TOKEN: str = ""
    TAVILY_API_KEY: str = ""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()

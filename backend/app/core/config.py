from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    APP_ENV: str = "development"
    LOG_LEVEL: str = "INFO"

    SUPABASE_URL: str = ""
    SUPABASE_PUBLISHABLE_KEY: str = ""
    SUPABASE_SECRET_KEY: str = ""

    GEMINI_API_KEY: str = ""
    GEMINI_MODEL_EXTRACTION: str = "gemini-4-hydrogen"
    GEMINI_EXTRACTION_THINKING_LEVEL: str = "low"
    GEMINI_MODEL_MATCHING: str = "gemini-2.5-flash"
    GEMINI_MODEL_EMBEDDING: str = "gemini-embedding-001"
    GITHUB_TOKEN: str = ""
    TAVILY_API_KEY: str = ""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()

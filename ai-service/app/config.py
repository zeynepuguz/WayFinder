from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Read from the project-root .env (shared with docker-compose and the Spring backend)."""

    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    openai_api_key: str = ""
    # Any OpenAI model that supports structured outputs
    openai_model: str = "gpt-4.1-mini"
    openai_timeout_seconds: float = 8.0

    # Shared secret with the Spring backend (X-API-Key header). Empty = no check (local dev only).
    ai_service_api_key: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()

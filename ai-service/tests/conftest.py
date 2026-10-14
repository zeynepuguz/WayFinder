import os

# Tests never report to Sentry: the project-root .env may hold the real AI_SENTRY_DSN, and the failure tests
# raise on purpose. Environment variables win over .env (pydantic-settings), and this runs before app is imported.
os.environ["AI_SENTRY_DSN"] = ""

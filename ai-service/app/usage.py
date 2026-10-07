"""Token usage of the OpenAI calls made for one request, returned to the backend as response headers.

The backend writes it into ai_usage (who, what, how many tokens, what it cost), so OpenAI spending can be followed
in the admin area. Only counts and the model name leave here, never the message or the photo.
"""
from contextvars import ContextVar
from dataclasses import dataclass


@dataclass
class Usage:
    model: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    calls: int = 0

    def add(self, response) -> None:
        """Adds one Responses API answer (moderation answers have no usage and cost nothing)."""
        usage = getattr(response, "usage", None)
        if usage is None:
            return
        self.model = getattr(response, "model", None) or self.model
        self.input_tokens += getattr(usage, "input_tokens", 0) or 0
        self.output_tokens += getattr(usage, "output_tokens", 0) or 0
        self.calls += 1

    def headers(self) -> dict[str, str]:
        if not self.calls:
            return {}
        return {
            "X-AI-Model": self.model,
            "X-AI-Input-Tokens": str(self.input_tokens),
            "X-AI-Output-Tokens": str(self.output_tokens),
        }


_current: ContextVar[Usage | None] = ContextVar("ai_usage", default=None)


def start() -> Usage:
    """A fresh counter for this request (sync endpoints run in their own thread and context)."""
    usage = Usage()
    _current.set(usage)
    return usage


def record(response) -> None:
    usage = _current.get()
    if usage is not None:
        usage.add(response)

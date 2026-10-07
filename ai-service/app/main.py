import logging
import secrets
import time
import uuid

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import usage
from .config import Settings, get_settings
from .intent import IntentExtractor, OpenAIIntentExtractor
from .photos import OpenAIPhotoVerifier, PhotoVerdict, PhotoVerifier, PhotoVerifyRequest
from .schemas import AssistantIntent, IntentRequest

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
log = logging.getLogger("nomi.ai")

app = FastAPI(title="Nomi AI Service", version="0.1.0")


@app.middleware("http")
async def request_logging(request: Request, call_next):
    # Same X-Request-Id as the Spring backend, so one request can be followed across both services
    request_id = request.headers.get("X-Request-Id") or uuid.uuid4().hex[:8]
    start = time.perf_counter()
    response = await call_next(request)
    millis = (time.perf_counter() - start) * 1000
    response.headers["X-Request-Id"] = request_id
    log.info("[%s] %s %s -> %s (%.0f ms)", request_id, request.method, request.url.path, response.status_code, millis)
    return response


def require_api_key(
    x_api_key: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    if not settings.ai_service_api_key:
        return
    if x_api_key is None or not secrets.compare_digest(x_api_key, settings.ai_service_api_key):
        raise HTTPException(status_code=401, detail="Invalid API key")


if not get_settings().ai_service_api_key:
    log.warning("AI_SERVICE_API_KEY is empty: requests are not checked (local development only)")


def require_openai_key(settings: Settings) -> None:
    # The backend treats this like any error: rule-based parser for intents, photo stays PENDING and is retried
    if not settings.openai_api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")


_extractor: IntentExtractor | None = None


def get_extractor(settings: Settings = Depends(get_settings)) -> IntentExtractor:
    global _extractor
    require_openai_key(settings)
    if _extractor is None:
        _extractor = OpenAIIntentExtractor(settings)
    return _extractor


@app.get("/health")
def health(settings: Settings = Depends(get_settings)) -> dict:
    return {"status": "UP", "llmConfigured": bool(settings.openai_api_key), "model": settings.openai_model}


@app.post("/v1/intent", response_model=AssistantIntent, dependencies=[Depends(require_api_key)])
def parse_intent(request: IntentRequest, response: Response,
                 extractor: IntentExtractor = Depends(get_extractor)) -> AssistantIntent:
    # Token counts go back as headers, also on failure (an unparsable answer was still paid for)
    used = usage.start()
    try:
        intent = extractor.extract(request)
    except HTTPException:
        raise
    except Exception as e:  # LLM timeout, rate limit, invalid output...
        log.warning("Intent extraction failed: %s", e)
        raise HTTPException(status_code=502, detail="Intent extraction failed", headers=used.headers()) from e
    response.headers.update(used.headers())
    return intent


_photo_verifier: PhotoVerifier | None = None


def get_photo_verifier(settings: Settings = Depends(get_settings)) -> PhotoVerifier:
    global _photo_verifier
    require_openai_key(settings)
    if _photo_verifier is None:
        _photo_verifier = OpenAIPhotoVerifier(settings)
    return _photo_verifier


@app.post("/v1/photos/verify", response_model=PhotoVerdict, dependencies=[Depends(require_api_key)])
def verify_photo(request: PhotoVerifyRequest, response: Response,
                 verifier: PhotoVerifier = Depends(get_photo_verifier)) -> PhotoVerdict:
    used = usage.start()
    try:
        verdict = verifier.verify(request)
    except HTTPException:
        raise
    except Exception as e:  # moderation / LLM timeout, rate limit, invalid output...
        # Never log the image; the target name is enough to follow it
        log.warning("Photo verification failed for %s '%s': %s", request.target_type, request.name, e)
        raise HTTPException(status_code=502, detail="Photo verification failed", headers=used.headers()) from e
    response.headers.update(used.headers())
    return verdict


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    # Same 422 as FastAPI's default, but never echoing the input back (it can be a whole base64 photo)
    errors = [{"loc": list(e.get("loc", ())), "msg": e.get("msg"), "type": e.get("type")} for e in exc.errors()]
    return JSONResponse(status_code=422, content={"detail": errors})

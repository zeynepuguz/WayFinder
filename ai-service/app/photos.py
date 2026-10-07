"""
Checks a user's photo of a place / district before the backend publishes it.

- Safety: OpenAI moderation (omni-moderation-latest reads images). Flagged -> safe = False.
- Relevance: a vision model judges whether the photo plausibly shows THIS place (or, for a district, a typical
  street / landmark / view of an urban area). If the place has a known Wikimedia photo, it is sent as a second
  image to compare against.

The backend has already checked WHERE the photo was taken (EXIF GPS / device position); this only looks at
WHAT it shows. Any error is reported as an error (never as "approved"): the backend retries later.
"""

import base64
import binascii
import logging
from typing import Literal, Protocol
from urllib.parse import urlparse

from pydantic import BaseModel, Field, field_validator

from . import usage
from .config import Settings, openai_client

log = logging.getLogger(__name__)

# 512 px JPEG from the backend is ~50-150 KB; anything far larger is not what the backend sends
MAX_IMAGE_BYTES = 2 * 1024 * 1024
# The only reference photos the backend has come from Wikimedia Commons; OpenAI fetches the URL itself,
# so nothing else is passed on
REFERENCE_HOSTS = ("upload.wikimedia.org", "thumb.wikimedia.org")


# ---------- request / response (snake_case, mirrors backend photo/PhotoAiClient) ----------

class PhotoVerifyRequest(BaseModel):
    image_base64: str = Field(min_length=16, max_length=3_000_000)
    target_type: Literal["PLACE", "DISTRICT"]
    name: str = Field(min_length=1, max_length=300)
    category: str | None = Field(default=None, max_length=50)
    city: str | None = Field(default=None, max_length=100)
    district: str | None = Field(default=None, max_length=100)
    reference_image_url: str | None = Field(default=None, max_length=1000)

    @field_validator("image_base64")
    @classmethod
    def must_be_an_image(cls, value: str) -> str:
        try:
            data = base64.b64decode(value, validate=True)
        except (binascii.Error, ValueError) as e:
            raise ValueError("image_base64 is not valid base64") from e
        if len(data) > MAX_IMAGE_BYTES:
            raise ValueError("image is too large")
        if image_mime(data) is None:
            raise ValueError("image must be JPEG, PNG or WebP")
        return value

    @field_validator("reference_image_url")
    @classmethod
    def only_wikimedia_references(cls, value: str | None) -> str | None:
        if not value:
            return None
        parsed = urlparse(value.strip())
        if parsed.scheme != "https" or parsed.hostname not in REFERENCE_HOSTS:
            return None
        return value.strip()


class PhotoVerdict(BaseModel):
    relevant: bool
    safe: bool
    people_focused: bool
    confidence: float = Field(ge=0, le=1)
    reason: str


# ---------- the vision model's structured output ----------
# No defaults: OpenAI strict structured outputs require every field.

class PhotoJudgement(BaseModel):
    relevant: bool = Field(description="The photo plausibly shows this place / area (see the rules)")
    people_focused: bool = Field(
        description="People (faces, selfies, group photos) are the main subject rather than the place")
    inappropriate: bool = Field(
        description="Nudity, violence, hate symbols, drugs, readable personal documents / screens, or other "
                    "content unfit for a family-friendly city guide")
    confidence: float = Field(description="0..1, how sure you are that 'relevant' is right")
    reason: str = Field(description="One short English sentence")


INSTRUCTIONS = """
You review photos that users of Nomi, a city guide app for Turkey, upload for a place or a district. Only real,
recent photos of the place itself are published. Their location was already verified by GPS; you judge the content.

relevant = true when the photo plausibly shows THIS place or this TYPE of place as a visitor would see it:
- café / breakfast / dessert place / restaurant: the interior, the exterior or entrance, the seating, the food,
  drinks or dishes served there.
- museum / culture venue: the building, its halls, exhibits, artworks.
- attraction / landmark: the landmark itself or the view from it. If a reference photo of the place is given,
  a well-known landmark must look like the same landmark (a different mosque, tower or bridge -> not relevant).
- park / seaside: the park, its paths, the green space, the shore, the view.
- district (target_type DISTRICT): a street, square, building, landmark, shoreline or city view typical of an
  urban area in Turkey.
relevant = false for: screenshots, memes, text documents, receipts, selfies, close-ups of people, pets as the
main subject, random objects, cars, dark / blurry / blank images, photos that clearly show a different kind of
place (e.g. a beach for a museum), drawings or AI-generated looking images. A menu board or the counter is fine
for a café or restaurant.
people_focused = true when a person or group is the main subject (selfie, portrait, posing group). People in the
background of a street or a busy café are fine (false).
inappropriate = true for content unfit for a family-friendly guide or that exposes private data (ID cards,
licence plates in close-up, screens with personal data).
Be strict: when unsure whether it shows the place, set relevant = false or a low confidence.
""".strip()


class PhotoVerifier(Protocol):
    def verify(self, request: PhotoVerifyRequest) -> PhotoVerdict: ...


def image_mime(data: bytes) -> str | None:
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def data_url(image_base64: str) -> str:
    mime = image_mime(base64.b64decode(image_base64)) or "image/jpeg"
    return f"data:{mime};base64,{image_base64}"


def describe(request: PhotoVerifyRequest) -> str:
    lines = [f"target_type: {request.target_type}"]
    if request.target_type == "PLACE":
        lines.append(f"place name: {request.name}")
        if request.category:
            lines.append(f"place category: {request.category}")
    else:
        lines.append(f"district: {request.name}")
    if request.district and request.target_type == "PLACE":
        lines.append(f"district: {request.district}")
    if request.city:
        lines.append(f"city: {request.city}, Turkey")
    lines.append("The first image is the user's photo.")
    return "\n".join(lines)


class OpenAIPhotoVerifier:
    def __init__(self, settings: Settings):
        self._model = settings.photo_verify_model or settings.openai_model
        self._moderation_model = settings.moderation_model
        self._client = openai_client(settings, settings.photo_verify_timeout_seconds)

    def verify(self, request: PhotoVerifyRequest) -> PhotoVerdict:
        image = data_url(request.image_base64)

        if self._flagged(image):
            return PhotoVerdict(relevant=False, safe=False, people_focused=False, confidence=1.0,
                                reason="Flagged by moderation")

        try:
            judgement = self._judge(request, image, request.reference_image_url)
        except Exception as e:
            if not request.reference_image_url:
                raise
            # The reference URL may be unreachable for OpenAI; judge the photo on its own
            log.info("Photo check with reference image failed (%s), retrying without it", type(e).__name__)
            judgement = self._judge(request, image, None)

        return PhotoVerdict(
            relevant=judgement.relevant,
            safe=not judgement.inappropriate,
            people_focused=judgement.people_focused,
            confidence=min(1.0, max(0.0, judgement.confidence)),
            reason=judgement.reason[:300],
        )

    def _flagged(self, image: str) -> bool:
        result = self._client.moderations.create(
            model=self._moderation_model,
            input=[{"type": "image_url", "image_url": {"url": image}}],
        )
        return any(r.flagged for r in result.results)

    def _judge(self, request: PhotoVerifyRequest, image: str, reference_url: str | None) -> PhotoJudgement:
        content = [
            {"type": "input_text", "text": describe(request)},
            {"type": "input_image", "image_url": image, "detail": "low"},
        ]
        if reference_url:
            content += [
                {"type": "input_text", "text": "The second image is the known reference photo of this place "
                                               "(Wikimedia Commons). Compare: a landmark must be the same one."},
                {"type": "input_image", "image_url": reference_url, "detail": "low"},
            ]
        response = self._client.responses.parse(
            model=self._model,
            instructions=INSTRUCTIONS,
            input=[{"role": "user", "content": content}],
            text_format=PhotoJudgement,
            temperature=0,
        )
        usage.record(response)
        judgement = response.output_parsed
        if judgement is None:
            raise ValueError("LLM returned no parsable photo judgement")
        return judgement

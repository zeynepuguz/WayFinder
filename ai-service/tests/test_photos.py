import base64

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.config import get_settings
from app.main import app, get_photo_verifier
from app.photos import OpenAIPhotoVerifier, PhotoJudgement, PhotoVerdict, PhotoVerifyRequest

# A tiny "JPEG": only the magic bytes matter to the service (the backend sends a real 512 px JPEG)
JPEG_B64 = base64.b64encode(b"\xff\xd8\xff\xe0" + b"\x00" * 60).decode()
WIKIMEDIA = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Galata.jpg/800px-Galata.jpg"

BODY = {
    "image_base64": JPEG_B64,
    "target_type": "PLACE",
    "name": "Galata Kulesi",
    "category": "ATTRACTION",
    "city": "İstanbul",
    "district": "Beyoğlu",
    "reference_image_url": WIKIMEDIA,
}


class FakePhotoVerifier:
    def __init__(self, verdict: PhotoVerdict | None = None, error: Exception | None = None):
        self.verdict = verdict
        self.error = error
        self.last_request: PhotoVerifyRequest | None = None

    def verify(self, request: PhotoVerifyRequest) -> PhotoVerdict:
        self.last_request = request
        if self.error:
            raise self.error
        return self.verdict


def client_with(verifier) -> TestClient:
    app.dependency_overrides[get_settings] = lambda: Settings(ai_service_api_key="secret", openai_api_key="x")
    app.dependency_overrides[get_photo_verifier] = lambda: verifier
    return TestClient(app)


def teardown_function():
    app.dependency_overrides.clear()


HEADERS = {"X-API-Key": "secret"}


def test_photo_verify_requires_the_api_key():
    client = client_with(FakePhotoVerifier(PhotoVerdict(relevant=True, safe=True, people_focused=False,
                                                        confidence=0.9, reason="ok")))

    assert client.post("/v1/photos/verify", json=BODY).status_code == 401


def test_photo_verify_returns_the_backend_verdict_shape():
    verifier = FakePhotoVerifier(PhotoVerdict(relevant=True, safe=True, people_focused=False, confidence=0.9,
                                              reason="The tower from the street"))
    client = client_with(verifier)

    response = client.post("/v1/photos/verify", json=BODY, headers=HEADERS)

    assert response.status_code == 200
    assert response.json() == {"relevant": True, "safe": True, "people_focused": False, "confidence": 0.9,
                               "reason": "The tower from the street"}
    assert verifier.last_request.reference_image_url == WIKIMEDIA


def test_photo_verify_errors_are_502_so_the_backend_retries():
    client = client_with(FakePhotoVerifier(error=TimeoutError("slow")))

    assert client.post("/v1/photos/verify", json=BODY, headers=HEADERS).status_code == 502


def test_photo_verify_without_openai_key_is_503():
    app.dependency_overrides[get_settings] = lambda: Settings(ai_service_api_key="", openai_api_key="")

    assert TestClient(app).post("/v1/photos/verify", json=BODY).status_code == 503


def test_invalid_image_is_422_without_echoing_it():
    client = client_with(FakePhotoVerifier())
    not_an_image = base64.b64encode(b"%PDF-1.7" + b"x" * 40).decode()

    response = client.post("/v1/photos/verify", json={**BODY, "image_base64": not_an_image}, headers=HEADERS)

    assert response.status_code == 422
    assert not_an_image not in response.text
    assert client.post("/v1/photos/verify", json={**BODY, "image_base64": "@@not base64@@@@"},
                       headers=HEADERS).status_code == 422


def test_only_wikimedia_reference_urls_are_passed_on():
    def reference(url):
        return PhotoVerifyRequest(**{**BODY, "reference_image_url": url}).reference_image_url

    assert reference(WIKIMEDIA) == WIKIMEDIA
    thumb = "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/22/H.jpg/960px-H.jpg"
    assert reference(thumb) == thumb
    assert reference("http://upload.wikimedia.org/x.jpg") is None
    assert reference("https://evil.example.com/x.jpg") is None
    assert reference("") is None


# ---------- OpenAIPhotoVerifier with a mocked OpenAI client ----------

class FakeOpenAI:
    def __init__(self, flagged=False, judgement=None, fail_with_reference=False):
        self.moderation_calls = []
        self.parse_calls = []
        outer = self

        class Moderations:
            def create(self, **kwargs):
                outer.moderation_calls.append(kwargs)
                result = type("Result", (), {"flagged": flagged})()
                return type("Moderation", (), {"results": [result]})()

        class Responses:
            def parse(self, **kwargs):
                outer.parse_calls.append(kwargs)
                images = [c for c in kwargs["input"][0]["content"] if c["type"] == "input_image"]
                if fail_with_reference and len(images) > 1:
                    raise RuntimeError("Error while downloading the reference image")
                return type("R", (), {"output_parsed": judgement})()

        self.moderations = Moderations()
        self.responses = Responses()


def verifier_with(fake: FakeOpenAI, monkeypatch) -> OpenAIPhotoVerifier:
    verifier = OpenAIPhotoVerifier(Settings(openai_api_key="x", openai_model="gpt-4.1-mini", photo_verify_model="",
                                            moderation_model="omni-moderation-latest"))
    monkeypatch.setattr(verifier, "_client", fake)
    return verifier


def judgement(**overrides) -> PhotoJudgement:
    values = {"relevant": True, "people_focused": False, "inappropriate": False, "confidence": 0.8,
              "reason": "Shows the tower"}
    values.update(overrides)
    return PhotoJudgement(**values)


def test_flagged_by_moderation_is_unsafe_without_asking_the_vision_model(monkeypatch):
    fake = FakeOpenAI(flagged=True, judgement=judgement())

    verdict = verifier_with(fake, monkeypatch).verify(PhotoVerifyRequest(**BODY))

    assert verdict.safe is False
    assert fake.parse_calls == []
    moderation = fake.moderation_calls[0]
    assert moderation["model"] == "omni-moderation-latest"
    assert moderation["input"][0]["image_url"]["url"].startswith("data:image/jpeg;base64,")


def test_vision_model_gets_context_both_images_in_low_detail_and_temperature_0(monkeypatch):
    fake = FakeOpenAI(judgement=judgement(people_focused=True, confidence=1.7))

    verdict = verifier_with(fake, monkeypatch).verify(PhotoVerifyRequest(**BODY))

    assert verdict == PhotoVerdict(relevant=True, safe=True, people_focused=True, confidence=1.0,
                                   reason="Shows the tower")
    call = fake.parse_calls[0]
    assert call["model"] == "gpt-4.1-mini"
    assert call["temperature"] == 0
    assert call["text_format"] is PhotoJudgement
    content = call["input"][0]["content"]
    assert "place name: Galata Kulesi" in content[0]["text"]
    assert "place category: ATTRACTION" in content[0]["text"]
    assert "city: İstanbul, Turkey" in content[0]["text"]
    images = [c for c in content if c["type"] == "input_image"]
    assert [i["detail"] for i in images] == ["low", "low"]
    assert images[1]["image_url"] == WIKIMEDIA
    assert any("reference photo" in c.get("text", "") for c in content)


def test_inappropriate_content_is_unsafe(monkeypatch):
    fake = FakeOpenAI(judgement=judgement(inappropriate=True))

    assert verifier_with(fake, monkeypatch).verify(PhotoVerifyRequest(**BODY)).safe is False


def test_unreachable_reference_image_is_retried_without_it(monkeypatch):
    fake = FakeOpenAI(judgement=judgement(), fail_with_reference=True)

    verdict = verifier_with(fake, monkeypatch).verify(PhotoVerifyRequest(**BODY))

    assert verdict.relevant is True
    assert len(fake.parse_calls) == 2
    second = [c for c in fake.parse_calls[1]["input"][0]["content"] if c["type"] == "input_image"]
    assert len(second) == 1


def test_district_prompt_and_errors_without_reference_propagate(monkeypatch):
    body = {**BODY, "target_type": "DISTRICT", "name": "Kadıköy", "category": None, "reference_image_url": None}
    fake = FakeOpenAI(judgement=None)

    with pytest.raises(ValueError):
        verifier_with(fake, monkeypatch).verify(PhotoVerifyRequest(**body))
    assert "district: Kadıköy" in fake.parse_calls[0]["input"][0]["content"][0]["text"]


def test_separate_photo_model_can_be_configured(monkeypatch):
    verifier = OpenAIPhotoVerifier(Settings(openai_api_key="x", openai_model="gpt-4.1-mini",
                                            photo_verify_model="gpt-4o-mini"))
    fake = FakeOpenAI(judgement=judgement())
    monkeypatch.setattr(verifier, "_client", fake)

    verifier.verify(PhotoVerifyRequest(**BODY))

    assert fake.parse_calls[0]["model"] == "gpt-4o-mini"

from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.intent import sanitize
from app.main import app, get_extractor
from app.schemas import (
    AssistantIntent, IntentContext, IntentRequest, IntentType, PlanParams, ReplanType, RouteEdit, StopType,
)


class FakeExtractor:
    """Stands in for the LLM so tests are fast, free and deterministic."""

    def __init__(self, intent: AssistantIntent | None = None, error: Exception | None = None):
        self.intent = intent
        self.error = error
        self.last_request: IntentRequest | None = None

    def extract(self, request: IntentRequest) -> AssistantIntent:
        self.last_request = request
        if self.error:
            raise self.error
        return sanitize(self.intent, request)


def plan_intent(**overrides) -> AssistantIntent:
    plan = PlanParams(partySize=2, budget=700, walkingTolerance=None, stops=[StopType.COFFEE],
                      interests=["history"], startTime=None)
    for key, value in overrides.items():
        setattr(plan, key, value)
    return AssistantIntent(type=IntentType.PLAN_ROUTE, plan=plan, edits=[], recommendType=None, source=None)


def client_with(extractor, api_key: str = "secret") -> TestClient:
    app.dependency_overrides[get_settings] = lambda: Settings(ai_service_api_key=api_key, openai_api_key="x")
    app.dependency_overrides[get_extractor] = lambda: extractor
    return TestClient(app)


def teardown_function():
    app.dependency_overrides.clear()


BACKEND_BODY = {
    "message": "Akşam yemeğini çıkar",
    "context": {"hasRoute": True, "remainingStops": [{"stopId": 5, "type": "DINNER", "placeName": "Çiya Sofrası"}]},
}


def test_rejects_missing_or_wrong_api_key():
    client = client_with(FakeExtractor(plan_intent()))

    assert client.post("/v1/intent", json=BACKEND_BODY).status_code == 401
    assert client.post("/v1/intent", json=BACKEND_BODY, headers={"X-API-Key": "wrong"}).status_code == 401


def test_accepts_backend_request_shape_and_returns_backend_intent_shape():
    extractor = FakeExtractor(plan_intent())
    client = client_with(extractor)

    response = client.post("/v1/intent", json=BACKEND_BODY, headers={"X-API-Key": "secret"})

    assert response.status_code == 200
    body = response.json()
    assert body["type"] == "PLAN_ROUTE"
    assert body["plan"]["budget"] == 700
    assert body["source"] == "ai"
    assert extractor.last_request.context.remainingStops[0].placeName == "Çiya Sofrası"


def test_llm_failure_returns_502_so_backend_falls_back():
    client = client_with(FakeExtractor(error=TimeoutError("slow")))

    response = client.post("/v1/intent", json=BACKEND_BODY, headers={"X-API-Key": "secret"})

    assert response.status_code == 502


def test_without_openai_key_returns_503():
    app.dependency_overrides[get_settings] = lambda: Settings(ai_service_api_key="", openai_api_key="")
    client = TestClient(app)

    assert client.post("/v1/intent", json=BACKEND_BODY).status_code == 503


def test_sanitize_removes_values_the_backend_does_not_know():
    intent = plan_intent(interests=["history", "nightlife"], startTime="25:99", partySize=500)

    result = sanitize(intent, IntentRequest(message="x"))

    assert result.plan.interests == ["history"]
    assert result.plan.startTime is None
    assert result.plan.partySize is None


def test_sanitize_drops_incomplete_edits():
    intent = AssistantIntent(
        type=IntentType.REPLAN,
        plan=None,
        edits=[
            RouteEdit(type=ReplanType.ADD_STOP, stopType=None, interest=None, targetText=None,
                      targetStopType=None, targetIsCurrent=False),
            RouteEdit(type=ReplanType.ADD_INTEREST, stopType=None, interest="history", targetText=None,
                      targetStopType=None, targetIsCurrent=False),
        ],
        recommendType=None,
        source=None,
    )

    result = sanitize(intent, IntentRequest(message="x", context=IntentContext(hasRoute=True)))

    assert [e.type for e in result.edits] == [ReplanType.ADD_INTEREST]
    assert result.type == IntentType.REPLAN


def test_health():
    app.dependency_overrides[get_settings] = lambda: Settings(openai_api_key="", openai_model="m")
    assert TestClient(app).get("/health").json() == {"status": "UP", "llmConfigured": False, "model": "m"}

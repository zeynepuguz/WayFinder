"""
Request/response models. They mirror the Spring backend's Java records exactly
(com.nomi.wayfinder.assistant.AssistantIntent and IntentParser.IntentContext),
so field names are camelCase on purpose.
"""

from enum import StrEnum

from pydantic import BaseModel, Field


class IntentType(StrEnum):
    PLAN_ROUTE = "PLAN_ROUTE"
    REPLAN = "REPLAN"
    RECOMMEND = "RECOMMEND"
    WEATHER = "WEATHER"
    SHOW_ROUTE = "SHOW_ROUTE"
    UNKNOWN = "UNKNOWN"


class StopType(StrEnum):
    BREAKFAST = "BREAKFAST"
    SIGHTSEEING = "SIGHTSEEING"
    LUNCH = "LUNCH"
    COFFEE = "COFFEE"
    DESSERT = "DESSERT"
    DINNER = "DINNER"


class WalkingTolerance(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class ReplanType(StrEnum):
    TIRED = "TIRED"
    WEATHER_CHANGED = "WEATHER_CHANGED"
    REMOVE_STOP = "REMOVE_STOP"
    REPLACE_STOP = "REPLACE_STOP"
    ADD_STOP = "ADD_STOP"
    ADD_INTEREST = "ADD_INTEREST"
    LESS_WALKING = "LESS_WALKING"


# Place tags in the backend database. The LLM may only use these as interests.
ALLOWED_INTERESTS = {
    "history", "museum", "sea", "nature", "art", "street-art", "view", "local", "books",
    "architecture", "seafood", "budget", "traditional", "shopping", "music", "sports",
}


# ---------- request from the backend ----------

class StopRef(BaseModel):
    stopId: int | None = None
    type: StopType | None = None
    placeName: str | None = Field(default=None, max_length=200)


class IntentContext(BaseModel):
    hasRoute: bool = False
    remainingStops: list[StopRef] = Field(default_factory=list, max_length=30)


class IntentRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1000)
    context: IntentContext = Field(default_factory=IntentContext)


# ---------- response to the backend (also the LLM's structured output) ----------
# No defaults: OpenAI strict structured outputs require every field to be present (null allowed).

class PlanParams(BaseModel):
    partySize: int | None
    budget: int | None = Field(description="Total budget in TL for the whole group")
    walkingTolerance: WalkingTolerance | None
    stops: list[StopType]
    interests: list[str]
    startTime: str | None = Field(description="HH:MM, only if the user said when to start")
    # Added later: the default keeps older payloads valid; OpenAI's strict schema still lists it as required (nullable)
    popular: bool | None = Field(
        default=None,
        description="true when the user wants the famous / must-see sights of the place (ünlü, meşhur, popüler)")


class RouteEdit(BaseModel):
    type: ReplanType
    stopType: StopType | None = Field(description="Only for ADD_STOP")
    interest: str | None = Field(description="Only for ADD_INTEREST, one of the allowed interests")
    targetText: str | None = Field(description="REMOVE/REPLACE: the place name as the user wrote it")
    targetStopType: StopType | None = Field(description="REMOVE/REPLACE: stop named by type, e.g. 'akşam yemeği'")
    targetIsCurrent: bool = Field(description="REMOVE/REPLACE: user means the current/next stop ('burayı')")


class AssistantIntent(BaseModel):
    type: IntentType
    plan: PlanParams | None
    edits: list[RouteEdit]
    recommendType: StopType | None
    source: str | None
    # Added later: defaults keep older payloads valid; OpenAI's strict schema still lists them as required (nullable)
    date: str | None = Field(
        default=None,
        description="The day the user means, YYYY-MM-DD, resolved from context.today; null = not said (today)")
    area: str | None = Field(
        default=None,
        description="The city, district or neighbourhood in Turkey the user wants to be in / start from, as written; null if none")

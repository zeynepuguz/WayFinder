"""
Turns a user's message into an AssistantIntent with an LLM.

The LLM only UNDERSTANDS the request. It never chooses places, prices, distances or
opening hours; the Spring backend does that with real data (PostGIS, weather, database).
"""

import json
import logging
import re
from typing import Protocol

from openai import OpenAI

from .config import Settings
from .schemas import ALLOWED_INTERESTS, AssistantIntent, IntentRequest, IntentType, ReplanType

log = logging.getLogger(__name__)

TIME_PATTERN = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")

INSTRUCTIONS = f"""
You are the intent parser of Nomi, a city companion app (MVP: Kadıköy, Istanbul).
Users write in Turkish. Convert the message into the given JSON structure. Do not answer the user.

Intent types:
- PLAN_ROUTE: the user wants a new day plan (mentions budget, group size, stops, "plan", "ne yapabiliriz", ...).
- REPLAN: the user wants to change their CURRENT route (only if context.hasRoute is true):
  TIRED ("yorulduk"), WEATHER_CHANGED ("yağmur başladı"), LESS_WALKING ("daha az yürüyelim"),
  REMOVE_STOP ("burayı çıkar"), REPLACE_STOP ("başka bir yer olsun"), ADD_STOP ("tatlı da ekle"),
  ADD_INTEREST ("biraz daha tarihi yer ekle"). One message may contain several edits, in order.
- RECOMMEND: asks for a suggestion of one kind of place nearby ("yakında kahve öner"). Set recommendType.
- WEATHER: asks about the weather.
- SHOW_ROUTE: asks to see the current route / next stop.
- UNKNOWN: anything else (greetings, unrelated questions).

Stop types: BREAKFAST (kahvaltı), SIGHTSEEING (gezilecek yer, müze, park, sahil, tarihi yer),
LUNCH (öğle yemeği, plain "yemek"), COFFEE (kahve), DESSERT (tatlı, dondurma), DINNER (akşam yemeği).
For a full-day trip request that lists food stops, also add two SIGHTSEEING stops.
Leave plan.stops empty if the user did not name any stops (the backend then plans a full day).

Interests must be chosen only from: {", ".join(sorted(ALLOWED_INTERESTS))}.
"uygun bütçe" / "ucuz" -> budget. "tarihi" -> history. "deniz/sahil" -> sea.

walkingTolerance: LOW if they do not want to walk much or are tired, HIGH if they like walking, else null.
budget: total TL for the whole group as a number (e.g. "700 TL" -> 700). partySize: number of people.
Weather mentioned inside a plan request ("hava çok sıcak") does not change the intent; real weather is fetched separately.

For REMOVE_STOP / REPLACE_STOP: copy the place name into targetText if the user named it
(prefer names from context.remainingStops), set targetStopType if they named the stop by type,
set targetIsCurrent if they said "burası/burayı/bunu".
Never invent place names. Unused fields must be null (or empty lists / false).
""".strip()


class IntentExtractor(Protocol):
    def extract(self, request: IntentRequest) -> AssistantIntent: ...


class OpenAIIntentExtractor:
    def __init__(self, settings: Settings):
        self._model = settings.openai_model
        self._client = OpenAI(
            api_key=settings.openai_api_key,
            timeout=settings.openai_timeout_seconds,
            max_retries=1,
        )

    def extract(self, request: IntentRequest) -> AssistantIntent:
        user_input = json.dumps(
            {"message": request.message, "context": request.context.model_dump()},
            ensure_ascii=False,
        )

        response = self._client.responses.parse(
            model=self._model,
            instructions=INSTRUCTIONS,
            input=user_input,
            text_format=AssistantIntent,
            temperature=0,
        )

        intent = response.output_parsed
        if intent is None:
            raise ValueError("LLM returned no parsable intent")

        return sanitize(intent, request)


def sanitize(intent: AssistantIntent, request: IntentRequest) -> AssistantIntent:
    """Guardrails: the backend must only receive values it understands."""
    if intent.plan is not None:
        intent.plan.interests = [i for i in intent.plan.interests if i in ALLOWED_INTERESTS]
        if intent.plan.startTime and not TIME_PATTERN.match(intent.plan.startTime):
            intent.plan.startTime = None
        if intent.plan.budget is not None and intent.plan.budget < 0:
            intent.plan.budget = None
        if intent.plan.partySize is not None and not 1 <= intent.plan.partySize <= 20:
            intent.plan.partySize = None

    # Drop edits that are missing what they need
    valid_edits = []
    for edit in intent.edits:
        if edit.type == ReplanType.ADD_INTEREST and edit.interest not in ALLOWED_INTERESTS:
            continue
        if edit.type == ReplanType.ADD_STOP and edit.stopType is None:
            continue
        valid_edits.append(edit)
    intent.edits = valid_edits

    # (REPLAN without a route is fine: the backend answers "you have no route yet")
    if intent.type == IntentType.REPLAN and not intent.edits:
        intent.type = IntentType.UNKNOWN
    if intent.type == IntentType.PLAN_ROUTE and intent.plan is None:
        intent.type = IntentType.UNKNOWN

    intent.source = "ai"
    return intent

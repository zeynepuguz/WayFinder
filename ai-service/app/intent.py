"""
Turns a user's message into an AssistantIntent with an LLM.

The LLM only UNDERSTANDS the request. It never chooses places, prices, distances or
opening hours; the Spring backend does that with real data (PostGIS, weather, database).
"""

import json
import logging
import re
from datetime import date, datetime, timedelta, timezone
from typing import Protocol

from openai import OpenAI

from .config import Settings
from .schemas import ALLOWED_INTERESTS, AssistantIntent, IntentRequest, IntentType, ReplanType

log = logging.getLogger(__name__)

TIME_PATTERN = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")
AREA_MAX_LENGTH = 100
# How far ahead a plan date may be (a typo'd year must not reach the backend)
MAX_DAYS_AHEAD = 366
# Istanbul is UTC+3 all year (no DST since 2016); a fixed offset needs no tzdata package on Windows
ISTANBUL = timezone(timedelta(hours=3))


def istanbul_today() -> date:
    return datetime.now(ISTANBUL).date()


INSTRUCTIONS = f"""
You are the intent parser of Nomi, a city companion app covering the cities of Turkey (all 81 provinces).
Users write in Turkish or in English (tourists). Understand both languages the same way and convert the
message into the given JSON structure. Do not answer the user. The JSON values (intent types, stop types,
interests, enums) are always the same English keys below, whatever language the message is in.

Intent types:
- PLAN_ROUTE: the user wants a new day plan (mentions budget, group size, stops, "plan", "ne yapabiliriz",
  "plan a day in Kadıköy", "what can we do", ...).
- REPLAN: the user wants to change their CURRENT route (only if context.hasRoute is true):
  TIRED ("yorulduk" / "we're tired"), WEATHER_CHANGED ("yağmur başladı" / "it started raining"),
  LESS_WALKING ("daha az yürüyelim" / "less walking"), REMOVE_STOP ("burayı çıkar" / "remove this"),
  REPLACE_STOP ("başka bir yer olsun" / "somewhere else"), ADD_STOP ("tatlı da ekle" / "add a coffee stop"),
  ADD_INTEREST ("biraz daha tarihi yer ekle" / "add more historical places"). One message may contain several edits, in order.
- RECOMMEND: asks for a suggestion of one kind of place nearby ("yakında kahve öner" / "recommend a café nearby"). Set recommendType.
- WEATHER: asks about the weather.
- SHOW_ROUTE: asks to see the current route / next stop.
- UNKNOWN: anything else (greetings, unrelated questions).

Stop types: BREAKFAST (kahvaltı / breakfast), SIGHTSEEING (gezilecek yer, müze, park, sahil, tarihi yer /
sights, museum, park, seaside, historical place), LUNCH (öğle yemeği, plain "yemek" / lunch, plain "food"),
COFFEE (kahve / coffee, café), DESSERT (tatlı, dondurma / dessert, ice cream), DINNER (akşam yemeği / dinner).
For a full-day trip request that lists food stops, also add two SIGHTSEEING stops.
Leave plan.stops empty if the user did not name any stops (the backend then plans a full day).

Interests must be chosen only from: {", ".join(sorted(ALLOWED_INTERESTS))}.
"uygun bütçe" / "ucuz" / "cheap" / "budget-friendly" -> budget. "tarihi" / "historical" -> history.
"deniz/sahil" / "sea/seaside" -> sea.

walkingTolerance: LOW if they do not want to walk much or are tired, HIGH if they like walking, else null.
budget: total TL for the whole group as a number (e.g. "700 TL" / "700 lira" -> 700). A budget per person ("kişi başı
1000 TL", "her birimizin 1000'er TL", "1000'er lira", "1000 TL each", "per person") is multiplied by partySize
(4 people, 1000 each -> 4000). partySize: number of people ("2 kişiyiz" / "we are 2 people" -> 2, "4 arkadaş" /
"4 friends" -> 4, "ailemle 3 kişi" -> 3).
popular: true when they want the famous / best-known / must-see sights of the place ("ünlü bir rota", "meşhur yerler",
"popüler rota", "görülmesi gereken yerler", "famous", "must-see", "highlights"), else null. Such a request is
PLAN_ROUTE; add history to interests unless they named other interests.
date: the day the plan / weather question is for, as YYYY-MM-DD. context.today is today's date in Istanbul
(with its weekday): "bugün"/"today" -> today, "yarın"/"tomorrow" -> today + 1, "yarından sonra"/"öbür gün"/
"day after tomorrow" -> today + 2, a weekday ("cumartesi", "on Saturday") -> its next occurrence (today only if
they also say "bugün"/"today"), "28 Eylül"/"September 28"/"28.09" -> that date (next year if it already passed).
null when no day is mentioned. Never a date before context.today.
area: the place in Turkey the user wants to be in or start from, as written: a city (il: "Ankara'da" -> "Ankara",
"in Antalya" -> "Antalya"), a district (ilçe: "üsküdarda gezeceğiz" -> "üsküdar", "Kadıköy'deyim" -> "Kadıköy") or a
neighbourhood (semt: "around Moda" -> "Moda"). When both a city and a place inside it are named, keep both, city
first ("İzmir Konak'ta" -> "İzmir Konak", "yarın Ankara'da Kızılay'dan başlayalım" -> "Ankara Kızılay"). Drop
Turkish case suffixes when you can. null if no city, district or neighbourhood is named. Never a venue name (café,
museum) and never invented.
startTime: "saat 13.00", "13:00", "13.00'da" -> "13:00". A bare "saat 1'de" is ambiguous -> null.
Weather mentioned inside a plan request ("hava çok sıcak" / "it's very hot") does not change the intent; real weather is fetched separately.

For REMOVE_STOP / REPLACE_STOP: copy the place name into targetText if the user named it
(prefer names from context.remainingStops), set targetStopType if they named the stop by type,
set targetIsCurrent if they said "burası/burayı/bunu" / "this place/here/this one".
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
        today = istanbul_today()
        context = request.context.model_dump()
        # The model cannot know the date; "yarın" / "on Saturday" are resolved from this
        context["today"] = f"{today.isoformat()} ({today.strftime('%A')})"
        user_input = json.dumps(
            {"message": request.message, "context": context},
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

        return sanitize(intent, request, today)


def sanitize(intent: AssistantIntent, request: IntentRequest, today: date | None = None) -> AssistantIntent:
    """Guardrails: the backend must only receive values it understands."""
    intent.date = clean_date(intent.date, today or istanbul_today())
    if intent.area is not None:
        area = intent.area.strip()
        intent.area = area if area and len(area) <= AREA_MAX_LENGTH else None

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


def clean_date(value: str | None, today: date) -> str | None:
    """An ISO date from today up to MAX_DAYS_AHEAD days ahead, else None (= today in the backend)."""
    if not value:
        return None
    try:
        parsed = date.fromisoformat(value.strip())
    except ValueError:
        return None
    if parsed < today or parsed > today + timedelta(days=MAX_DAYS_AHEAD):
        return None
    return parsed.isoformat()

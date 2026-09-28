package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.assistant.AssistantIntent.PlanParams;
import com.nomi.wayfinder.assistant.AssistantIntent.RouteEdit;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.planning.ReplanType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword based Turkish + basic English parser. Works without any AI service and is the fallback when the
 * AI service is down. Deliberately simple: it only has to cover the common sentences.
 *
 * Turkish keywords are substrings (Turkish adds suffixes: "kahvaltı" / "kahvaltıda").
 * English keywords are whole words or phrases, so they never match inside Turkish words
 * ("art" must not match "artık").
 */
@Component
public class RuleBasedIntentParser implements IntentParser {

    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    private static final Pattern PARTY_DIGITS = Pattern.compile("(\\d{1,2})\\s*kişi");
    private static final Pattern PARTY_DIGITS_EN = Pattern.compile(
            "(?<![\\d.,])(\\d{1,2})\\s*(?:people|persons|person|adults|of us|pax)(?!\\p{L})"
                    + "|(?:party|group) of (\\d{1,2})(?!\\d)");
    private static final Pattern BUDGET = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})+|\\d{2,6})\\s*(?:tl|₺|lira)");
    // "budget of 700", "budget is 700", "₺700", "tl 700"
    private static final Pattern BUDGET_EN = Pattern.compile(
            "(?:budget(?:\\s+(?:of|is))?\\s*:?\\s*(?:₺|tl)?|₺|(?<!\\p{L})tl)\\s*(\\d{1,3}(?:[.,]\\d{3})+|\\d{2,6})(?![\\d])");
    private static final Pattern START_TIME = Pattern.compile("saat\\s*(\\d{1,2})(?:[:.](\\d{2}))?(?![\\d.,:])");
    // "saat 1'de" usually means 13:00 in speech: a bare hour is only trusted from 07 on
    private static final int EARLIEST_BARE_HOUR = 7;
    // "13:00", "13.00'da" without "saat" (not prices like "2.50 tl" or dates like "12.05.2026")
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "(?<![\\d.,:/])([01]?\\d|2[0-3])[:.]([0-5]\\d)(?![\\d]|[.,:/]\\d|\\s*(?:tl|₺|lira|km|kişi|%))");
    // "at 10", "from 10:30", "start at 9am", "10 am"
    private static final Pattern START_TIME_EN = Pattern.compile(
            "(?:(?<!\\p{L})(?:at|from|around)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?"
                    + "|(?<![\\d.,:])(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.))"
                    + "(?![\\d])(?!\\s*(?:people|persons|person|kişi|tl|₺|lira|min|km|m\\b))");
    // Clauses are handled separately: "akşam yemeğini çıkar, onun yerine tatlı ekle" / "remove dinner and add dessert"
    private static final Pattern CLAUSE_SPLIT = Pattern.compile(
            "[,.;!?\\n]|\\byerine\\b|\\bsonra\\b|\\binstead\\b|\\bthen\\b"
                    + "|\\band\\s+(?=(?:add|remove|drop|skip|replace|change|swap|cancel|delete|include|put)\\b)");

    private static final Map<String, Integer> PARTY_WORDS = new LinkedHashMap<>();
    private static final Map<String, Integer> PARTY_WORDS_EN = new LinkedHashMap<>();
    private static final Map<String, String> INTEREST_KEYWORDS = new LinkedHashMap<>();
    private static final Map<String, String> INTEREST_KEYWORDS_EN = new LinkedHashMap<>();
    private static final Map<String, Pattern> WORD_PATTERNS = new ConcurrentHashMap<>();

    private final Clock clock;

    @Autowired
    public RuleBasedIntentParser(Clock clock) {
        this.clock = clock;
    }

    // Tests that do not care about dates
    public RuleBasedIntentParser() {
        this(Clock.system(ZoneId.of("Europe/Istanbul")));
    }

    static {
        PARTY_WORDS.put("tek başıma", 1);
        PARTY_WORDS.put("yalnızım", 1);
        PARTY_WORDS.put("iki kişi", 2);
        PARTY_WORDS.put("ikimiz", 2);
        PARTY_WORDS.put("sevgilimle", 2);
        PARTY_WORDS.put("eşimle", 2);
        PARTY_WORDS.put("arkadaşımla", 2);
        PARTY_WORDS.put("üç kişi", 3);
        PARTY_WORDS.put("üçümüz", 3);
        PARTY_WORDS.put("dört kişi", 4);
        PARTY_WORDS.put("dördümüz", 4);
        PARTY_WORDS.put("beş kişi", 5);

        PARTY_WORDS_EN.put("by myself", 1);
        PARTY_WORDS_EN.put("on my own", 1);
        PARTY_WORDS_EN.put("alone", 1);
        PARTY_WORDS_EN.put("solo", 1);
        PARTY_WORDS_EN.put("just me", 1);
        PARTY_WORDS_EN.put("two people", 2);
        PARTY_WORDS_EN.put("two of us", 2);
        PARTY_WORDS_EN.put("a couple", 2);
        PARTY_WORDS_EN.put("with my partner", 2);
        PARTY_WORDS_EN.put("with my wife", 2);
        PARTY_WORDS_EN.put("with my husband", 2);
        PARTY_WORDS_EN.put("with my girlfriend", 2);
        PARTY_WORDS_EN.put("with my boyfriend", 2);
        PARTY_WORDS_EN.put("with my friend", 2);
        PARTY_WORDS_EN.put("with a friend", 2);
        PARTY_WORDS_EN.put("three people", 3);
        PARTY_WORDS_EN.put("three of us", 3);
        PARTY_WORDS_EN.put("four people", 4);
        PARTY_WORDS_EN.put("four of us", 4);
        PARTY_WORDS_EN.put("five people", 5);
        PARTY_WORDS_EN.put("five of us", 5);

        INTEREST_KEYWORDS.put("tarih", "history");
        INTEREST_KEYWORDS.put("müze", "museum");
        INTEREST_KEYWORDS.put("deniz", "sea");
        INTEREST_KEYWORDS.put("sahil", "sea");
        INTEREST_KEYWORDS.put("doğa", "nature");
        INTEREST_KEYWORDS.put("park", "nature");
        INTEREST_KEYWORDS.put("sokak sanat", "street-art");
        INTEREST_KEYWORDS.put("grafiti", "street-art");
        INTEREST_KEYWORDS.put("duvar resim", "street-art");
        INTEREST_KEYWORDS.put("sanat", "art");
        INTEREST_KEYWORDS.put("manzara", "view");
        INTEREST_KEYWORDS.put("gün batımı", "view");
        INTEREST_KEYWORDS.put("yerel", "local");
        INTEREST_KEYWORDS.put("sahaf", "books");
        INTEREST_KEYWORDS.put("kitap", "books");
        INTEREST_KEYWORDS.put("mimari", "architecture");
        INTEREST_KEYWORDS.put("balık", "seafood");
        INTEREST_KEYWORDS.put("uygun bütçe", "budget");
        INTEREST_KEYWORDS.put("ucuz", "budget");
        INTEREST_KEYWORDS.put("ekonomik", "budget");

        for (String w : List.of("history", "historical", "historic")) INTEREST_KEYWORDS_EN.put(w, "history");
        for (String w : List.of("museum", "museums")) INTEREST_KEYWORDS_EN.put(w, "museum");
        for (String w : List.of("sea", "seaside", "coast", "waterfront", "seafront", "beach")) INTEREST_KEYWORDS_EN.put(w, "sea");
        for (String w : List.of("nature", "park", "parks", "green")) INTEREST_KEYWORDS_EN.put(w, "nature");
        for (String w : List.of("street art", "graffiti", "mural", "murals")) INTEREST_KEYWORDS_EN.put(w, "street-art");
        for (String w : List.of("art", "arts", "gallery", "galleries")) INTEREST_KEYWORDS_EN.put(w, "art");
        for (String w : List.of("view", "views", "sunset", "sunsets", "scenery")) INTEREST_KEYWORDS_EN.put(w, "view");
        INTEREST_KEYWORDS_EN.put("local", "local");
        for (String w : List.of("books", "bookshop", "bookshops", "bookstore", "bookstores", "booksellers")) INTEREST_KEYWORDS_EN.put(w, "books");
        INTEREST_KEYWORDS_EN.put("architecture", "architecture");
        for (String w : List.of("fish", "seafood")) INTEREST_KEYWORDS_EN.put(w, "seafood");
        for (String w : List.of("cheap", "budget-friendly", "budget friendly", "affordable", "inexpensive", "on a budget", "low budget"))
            INTEREST_KEYWORDS_EN.put(w, "budget");
    }

    @Override
    public AssistantIntent parse(String message, IntentContext context) {
        AssistantIntent intent = parseWithoutDate(message, context);
        // The area is found by AreaResolver (it needs the district / neighbourhood list from the database)
        return intent.withDateAndArea(date(message, LocalDate.now(clock)), null);
    }

    // "yarın", "cumartesi", "28 Eylül", ... (null = not said = today)
    static LocalDate date(String message, LocalDate today) {
        return IntentDates.parse(message, today);
    }

    private AssistantIntent parseWithoutDate(String message, IntentContext context) {
        String text = message.toLowerCase(TR).replace('’', '\'').trim();

        // ---- 1) Changes to the current route ----
        if (context.hasRoute()) {
            List<RouteEdit> edits = parseEdits(text);
            // Budget / party size / "plan" describe a new day, not a change to the current one
            boolean newPlan = mentionsNewPlan(text) || budget(text) != null || partySize(text) != null;
            // "Çok yürümek istemiyoruz, kahvaltı ve kahve istiyoruz" is a plan request, not a replan
            boolean onlySituational = edits.stream().allMatch(e -> e.type() == ReplanType.TIRED
                    || e.type() == ReplanType.WEATHER_CHANGED || e.type() == ReplanType.LESS_WALKING);
            if (onlySituational && stopTypes(text).size() >= 2) {
                newPlan = true;
            }
            if (!edits.isEmpty() && !newPlan) {
                return new AssistantIntent(IntentType.REPLAN, null, edits, null, "rules");
            }
        }

        // ---- 2) Simple questions ----
        if ((containsAny(text, "rotam", "rotamı göster", "sıradaki", "sonraki durak")
                || hasWord(text, "my route", "show the route", "show route", "next stop", "what's next", "whats next",
                "where next")) && context.hasRoute()) {
            return new AssistantIntent(IntentType.SHOW_ROUTE, null, List.of(), null, "rules");
        }

        boolean planWords = mentionsNewPlan(text) || containsAny(text, "bütçe", "kişiyiz", "günlük", "bugün", "gün ")
                || hasWord(text, "budget", "people", "for the day", "a day");
        List<StopType> stops = stopTypes(text);

        if (!planWords && (containsAny(text, "hava", "yağmur yağacak", "sıcaklık", "derece")
                || hasWord(text, "weather", "forecast", "temperature", "degrees", "will it rain", "is it raining",
                "how hot", "how cold"))) {
            return new AssistantIntent(IntentType.WEATHER, null, List.of(), null, "rules");
        }

        if (!planWords && stops.size() == 1 && (containsAny(text, "öner", "nerede", "yakın", "nereye", "bul")
                || hasWord(text, "recommend", "suggest", "suggestion", "near", "nearby", "close by", "around here",
                "where", "find", "any good"))) {
            return new AssistantIntent(IntentType.RECOMMEND, null, List.of(), stops.getFirst(), "rules");
        }

        // ---- 3) New day plan ----
        if (planWords || !stops.isEmpty() || budget(text) != null) {
            List<StopType> planStops = new ArrayList<>(stops);
            // "gezi / gezmek / gezilecek" in a plan with explicit stops means: add sightseeing too
            if (!planStops.isEmpty()
                    && (containsAny(text, "gez", "günlük", "bir gün")
                    || hasWord(text, "a day", "day trip", "explore", "sightseeing", "whole day", "full day"))
                    && !planStops.contains(StopType.SIGHTSEEING)) {
                planStops.add(StopType.SIGHTSEEING);
                planStops.add(StopType.SIGHTSEEING);
            }

            PlanParams plan = new PlanParams(
                    partySize(text),
                    budget(text),
                    walking(text),
                    planStops,
                    interests(text),
                    startTime(text)
            );
            return new AssistantIntent(IntentType.PLAN_ROUTE, plan, List.of(), null, "rules");
        }

        if (stops.size() == 1) {
            return new AssistantIntent(IntentType.RECOMMEND, null, List.of(), stops.getFirst(), "rules");
        }

        return new AssistantIntent(IntentType.UNKNOWN, null, List.of(), null, "rules");
    }

    private List<RouteEdit> parseEdits(String text) {
        List<RouteEdit> edits = new ArrayList<>();

        if (containsAny(text, "yoruldu", "yorgun", "yorulduk", "bitkin")
                || hasWord(text, "tired", "exhausted", "worn out")) {
            edits.add(RouteEdit.of(ReplanType.TIRED));
        }
        if (containsAny(text, "yağmur başla", "yağmur yağıyor", "yağmur geldi", "ıslandık", "sağanak")
                || hasWord(text, "started raining", "started to rain", "starting to rain", "began raining",
                "began to rain", "it's raining", "its raining", "it is raining", "raining now", "raining again", "rain started",
                "got wet", "pouring", "downpour")) {
            edits.add(RouteEdit.of(ReplanType.WEATHER_CHANGED));
        }
        if (containsAny(text, "çok yürümek istemiyor", "daha az yürü", "az yürü", "yürümek istemiyor")
                || lessWalkingEnglish(text)) {
            edits.add(RouteEdit.of(ReplanType.LESS_WALKING));
        }

        for (String clause : CLAUSE_SPLIT.split(text)) {
            String c = clause.trim();
            if (c.isEmpty()) {
                continue;
            }

            List<StopType> types = stopTypes(c);
            StopType targetType = types.isEmpty() ? null : types.getFirst();
            boolean current = containsAny(c, "bura", "bunu", "şurayı")
                    || hasWord(c, "this", "here", "it", "this one", "this place", "this stop");

            if (containsAny(c, "çıkar", "kaldır", "iptal", "atla", "gitmeyelim")
                    || hasWord(c, "remove", "drop", "skip", "cancel", "delete", "take out", "get rid of",
                    "don't want to go", "do not want to go")) {
                edits.add(new RouteEdit(ReplanType.REMOVE_STOP, null, null, c, targetType, current));
            } else if (containsAny(c, "değiştir", "başka bir", "başka yer", "beğenmedi")
                    || hasWord(c, "replace", "change", "swap", "somewhere else", "another place", "different place",
                    "don't like", "do not like")) {
                edits.add(new RouteEdit(ReplanType.REPLACE_STOP, null, null, c, targetType, current));
            } else if (containsAny(c, "ekle", "de olsun", "da olsun", "gidelim")
                    || hasWord(c, "add", "include", "put in", "throw in", "squeeze in")) {
                List<String> interests = interests(c);
                if (!interests.isEmpty() && (types.isEmpty() || types.equals(List.of(StopType.SIGHTSEEING)))) {
                    interests.forEach(i -> edits.add(new RouteEdit(ReplanType.ADD_INTEREST, null, i, null, null, false)));
                } else {
                    types.forEach(t -> edits.add(new RouteEdit(ReplanType.ADD_STOP, t, null, null, null, false)));
                }
            }
        }

        return edits;
    }

    static List<StopType> stopTypes(String text) {
        List<StopType> types = new ArrayList<>();

        if (text.contains("kahvaltı") || hasWord(text, "breakfast", "brunch")) {
            types.add(StopType.BREAKFAST);
        }
        if (containsAny(text, "öğle yemeğ", "öğlen yemeğ", "öğle ") || hasWord(text, "lunch")) {
            types.add(StopType.LUNCH);
        }
        if (text.contains("kahve") || hasWord(text, "coffee", "café", "cafe", "cafés", "cafes")) {
            types.add(StopType.COFFEE);
        }
        if (containsAny(text, "tatlı", "dondurma", "pasta", "baklava", "lokum")
                || hasWord(text, "dessert", "desserts", "sweet", "sweets", "ice cream", "cake", "turkish delight")) {
            types.add(StopType.DESSERT);
        }
        if (containsAny(text, "akşam yemeğ", "akşam ye") || hasWord(text, "dinner", "supper")) {
            types.add(StopType.DINNER);
        }
        // A plain "yemek" without a meal time: lunch, unless lunch/dinner was already mentioned
        if ((containsAny(text, "yemek", "yemeği") || hasWord(text, "food", "meal", "eat", "restaurant"))
                && !types.contains(StopType.LUNCH) && !types.contains(StopType.DINNER)) {
            types.add(StopType.LUNCH);
        }
        if (containsAny(text, "tarihi", "müze", "görülecek", "gezilecek", "sahil", "park", "manzara", "sanat")
                || hasWord(text, "historical", "historic", "history", "museum", "museums", "sights", "sightseeing",
                "attractions", "landmarks", "places to see", "places to visit", "seaside", "coast", "waterfront",
                "parks", "view", "views", "art", "gallery")) {
            types.add(StopType.SIGHTSEEING);
        }

        return types;
    }

    static List<String> interests(String text) {
        List<String> result = new ArrayList<>();
        INTEREST_KEYWORDS.forEach((keyword, interest) -> {
            if (text.contains(keyword) && !result.contains(interest)) {
                result.add(interest);
            }
        });
        INTEREST_KEYWORDS_EN.forEach((keyword, interest) -> {
            if (hasWord(text, keyword) && !result.contains(interest)) {
                result.add(interest);
            }
        });
        // "sokak sanatı" also contains "sanat"
        if (result.contains("street-art")) {
            result.remove("art");
        }
        return result;
    }

    static Integer partySize(String text) {
        Matcher m = PARTY_DIGITS.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        for (Map.Entry<String, Integer> e : PARTY_WORDS.entrySet()) {
            if (text.contains(e.getKey())) {
                return e.getValue();
            }
        }
        Matcher en = PARTY_DIGITS_EN.matcher(english(text));
        if (en.find()) {
            return Integer.parseInt(en.group(1) != null ? en.group(1) : en.group(2));
        }
        for (Map.Entry<String, Integer> e : PARTY_WORDS_EN.entrySet()) {
            if (hasWord(text, e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    static Integer budget(String text) {
        Matcher m = BUDGET.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1).replaceAll("[.,]", ""));
        }
        Matcher en = BUDGET_EN.matcher(english(text));
        if (en.find()) {
            return Integer.parseInt(en.group(1).replaceAll("[.,]", ""));
        }
        return null;
    }

    static WalkingTolerance walking(String text) {
        if (containsAny(text, "çok yürümek istemiyor", "az yürü", "yürümek istemiyor", "yürüyemiyor", "yorgun")
                || lessWalkingEnglish(text) || hasWord(text, "tired")) {
            return WalkingTolerance.LOW;
        }
        if (containsAny(text, "yürümeyi sev", "çok yürüyebil", "yürümek sorun değil", "bol bol yürü")
                || hasWord(text, "love walking", "like walking", "enjoy walking", "don't mind walking",
                "happy to walk", "lots of walking", "walk a lot")) {
            return WalkingTolerance.HIGH;
        }
        return null;
    }

    static java.time.LocalTime startTime(String text) {
        Matcher m = START_TIME.matcher(text);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (hour > 23 || minute > 59) {
                return null;
            }
            // "saat 1'de": 01:00 or 13:00? Better no start time than a wrong one
            if (m.group(2) == null && hour < EARLIEST_BARE_HOUR) {
                return null;
            }
            return java.time.LocalTime.of(hour, minute);
        }

        Matcher clockTime = CLOCK_TIME.matcher(text);
        if (clockTime.find()) {
            return java.time.LocalTime.of(Integer.parseInt(clockTime.group(1)), Integer.parseInt(clockTime.group(2)));
        }

        Matcher en = START_TIME_EN.matcher(english(text));
        if (!en.find()) {
            return null;
        }
        boolean first = en.group(1) != null;
        int hour = Integer.parseInt(first ? en.group(1) : en.group(4));
        String minutes = first ? en.group(2) : en.group(5);
        String suffix = first ? en.group(3) : en.group(6);
        int minute = minutes == null ? 0 : Integer.parseInt(minutes);
        if (suffix != null) {
            if (hour < 1 || hour > 12) {
                return null;
            }
            boolean pm = suffix.startsWith("p");
            hour = hour % 12 + (pm ? 12 : 0);
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        return java.time.LocalTime.of(hour, minute);
    }

    private static boolean lessWalkingEnglish(String text) {
        return hasWord(text, "less walking", "walk less", "not walk much", "not walk a lot", "don't want to walk",
                "do not want to walk", "don't want to walk much", "too much walking", "can't walk", "cannot walk",
                "shorter walks", "fewer walks", "closer places", "closer together", "walking less");
    }

    private static boolean mentionsNewPlan(String text) {
        return containsAny(text, "plan", "rota oluştur", "yeni rota", "rota hazırla", "rota öner", "rota yap",
                // "yarın Ankara'da Kızılay'dan başlayan bir rota", "Kadıköy'de bir rota istiyorum"
                "bir rota", "başlayan rota", "rota istiyor", "rota ister",
                "ne yapabilir",
                "ne yapılır", "ne yapsak", "gezi yap", "gezmek istiyor", "ilk defa", "ilk kez")
                || hasWord(text, "itinerary", "a route", "new route", "day trip", "day out", "a day in",
                "what can we do", "what can i do", "what should we do", "what should i do", "what to do",
                "first time", "spend the day", "spend a day", "for a day", "one day", "whole day", "full day");
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whole-word/phrase match for English keywords. The text was lowercased with Turkish rules,
     * so "It" became "ıt"; English matching undoes that.
     */
    private static boolean hasWord(String text, String... words) {
        String en = english(text);
        for (String w : words) {
            Pattern p = WORD_PATTERNS.computeIfAbsent(w,
                    k -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + "(?![\\p{L}\\p{N}])"));
            if (p.matcher(en).find()) {
                return true;
            }
        }
        return false;
    }

    private static String english(String text) {
        return text.replace('ı', 'i');
    }
}

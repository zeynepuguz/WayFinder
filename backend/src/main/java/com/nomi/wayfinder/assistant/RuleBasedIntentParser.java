package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.assistant.AssistantIntent.PlanParams;
import com.nomi.wayfinder.assistant.AssistantIntent.RouteEdit;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.planning.ReplanType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword based Turkish parser. Works without any AI service and is the fallback when the
 * AI service is down. Deliberately simple: it only has to cover the common sentences.
 */
@Component
public class RuleBasedIntentParser implements IntentParser {

    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    private static final Pattern PARTY_DIGITS = Pattern.compile("(\\d{1,2})\\s*kişi");
    private static final Pattern BUDGET = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})+|\\d{2,6})\\s*(?:tl|₺|lira)");
    private static final Pattern START_TIME = Pattern.compile("saat\\s*(\\d{1,2})(?:[:.](\\d{2}))?");
    // Clauses are handled separately: "akşam yemeğini çıkar, onun yerine tatlı ekle"
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[,.;!?\\n]|\\byerine\\b|\\bsonra\\b");

    private static final Map<String, Integer> PARTY_WORDS = new LinkedHashMap<>();
    private static final Map<String, String> INTEREST_KEYWORDS = new LinkedHashMap<>();

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
    }

    @Override
    public AssistantIntent parse(String message, IntentContext context) {
        String text = message.toLowerCase(TR).trim();

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
        if (containsAny(text, "rotam", "rotamı göster", "sıradaki", "sonraki durak") && context.hasRoute()) {
            return new AssistantIntent(IntentType.SHOW_ROUTE, null, List.of(), null, "rules");
        }

        boolean planWords = mentionsNewPlan(text) || containsAny(text, "bütçe", "kişiyiz", "günlük", "bugün", "gün ");
        List<StopType> stops = stopTypes(text);

        if (!planWords && containsAny(text, "hava", "yağmur yağacak", "sıcaklık", "derece")) {
            return new AssistantIntent(IntentType.WEATHER, null, List.of(), null, "rules");
        }

        if (!planWords && stops.size() == 1 && containsAny(text, "öner", "nerede", "yakın", "nereye", "bul")) {
            return new AssistantIntent(IntentType.RECOMMEND, null, List.of(), stops.getFirst(), "rules");
        }

        // ---- 3) New day plan ----
        if (planWords || !stops.isEmpty() || budget(text) != null) {
            List<StopType> planStops = new ArrayList<>(stops);
            // "gezi / gezmek / gezilecek" in a plan with explicit stops means: add sightseeing too
            if (!planStops.isEmpty() && containsAny(text, "gez", "günlük", "bir gün") && !planStops.contains(StopType.SIGHTSEEING)) {
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

        if (containsAny(text, "yoruldu", "yorgun", "yorulduk", "bitkin")) {
            edits.add(RouteEdit.of(ReplanType.TIRED));
        }
        if (containsAny(text, "yağmur başla", "yağmur yağıyor", "yağmur geldi", "ıslandık", "sağanak")) {
            edits.add(RouteEdit.of(ReplanType.WEATHER_CHANGED));
        }
        if (containsAny(text, "çok yürümek istemiyor", "daha az yürü", "az yürü", "yürümek istemiyor")) {
            edits.add(RouteEdit.of(ReplanType.LESS_WALKING));
        }

        for (String clause : CLAUSE_SPLIT.split(text)) {
            String c = clause.trim();
            if (c.isEmpty()) {
                continue;
            }

            List<StopType> types = stopTypes(c);
            StopType targetType = types.isEmpty() ? null : types.getFirst();
            boolean current = containsAny(c, "bura", "bunu", "şurayı");

            if (containsAny(c, "çıkar", "kaldır", "iptal", "atla", "gitmeyelim")) {
                edits.add(new RouteEdit(ReplanType.REMOVE_STOP, null, null, c, targetType, current));
            } else if (containsAny(c, "değiştir", "başka bir", "başka yer", "beğenmedi")) {
                edits.add(new RouteEdit(ReplanType.REPLACE_STOP, null, null, c, targetType, current));
            } else if (containsAny(c, "ekle", "de olsun", "da olsun", "gidelim")) {
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

        if (text.contains("kahvaltı")) {
            types.add(StopType.BREAKFAST);
        }
        if (containsAny(text, "öğle yemeğ", "öğlen yemeğ", "öğle ")) {
            types.add(StopType.LUNCH);
        }
        if (text.contains("kahve")) {
            types.add(StopType.COFFEE);
        }
        if (containsAny(text, "tatlı", "dondurma", "pasta", "baklava", "lokum")) {
            types.add(StopType.DESSERT);
        }
        if (containsAny(text, "akşam yemeğ", "akşam ye")) {
            types.add(StopType.DINNER);
        }
        // A plain "yemek" without a meal time: lunch, unless lunch/dinner was already mentioned
        if (containsAny(text, "yemek", "yemeği") && !types.contains(StopType.LUNCH) && !types.contains(StopType.DINNER)) {
            types.add(StopType.LUNCH);
        }
        if (containsAny(text, "tarihi", "müze", "görülecek", "gezilecek", "sahil", "park", "manzara", "sanat")) {
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
        return null;
    }

    static Integer budget(String text) {
        Matcher m = BUDGET.matcher(text);
        if (!m.find()) {
            return null;
        }
        return Integer.parseInt(m.group(1).replaceAll("[.,]", ""));
    }

    static WalkingTolerance walking(String text) {
        if (containsAny(text, "çok yürümek istemiyor", "az yürü", "yürümek istemiyor", "yürüyemiyor", "yorgun")) {
            return WalkingTolerance.LOW;
        }
        if (containsAny(text, "yürümeyi sev", "çok yürüyebil", "yürümek sorun değil", "bol bol yürü")) {
            return WalkingTolerance.HIGH;
        }
        return null;
    }

    static java.time.LocalTime startTime(String text) {
        Matcher m = START_TIME.matcher(text);
        if (!m.find()) {
            return null;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return java.time.LocalTime.of(hour, minute);
    }

    private static boolean mentionsNewPlan(String text) {
        return containsAny(text, "plan", "rota oluştur", "yeni rota", "rota hazırla", "ne yapabilir",
                "ne yapılır", "ne yapsak", "gezi yap", "gezmek istiyor", "ilk defa", "ilk kez");
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}

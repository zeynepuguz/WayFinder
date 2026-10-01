package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Interest tags (service/Interests vocabulary) derived from what OSM states about a place: its cuisine, tourism /
 * historic / leisure / amenity / artwork tags and words of its name. Nothing is guessed beyond that: a restaurant
 * named "Köfteci Yusuf" serves köfte (local), a cuisine=seafood place is seafood, a çay bahçesi is budget-friendly.
 * Used by the import (OsmPlaceMapper, with the OSM tags) and for rows already in the database (only name, category
 * and the stored cuisine are known; PlaceDataNormalizer). Pure logic, unit tested.
 */
public final class PlaceTags {

    // OSM cuisine values of Turkish / regional food
    private static final Set<String> LOCAL_CUISINES = Set.of("turkish", "kebab", "regional", "meze", "pide",
            "lahmacun", "kofte", "köfte", "doner", "döner", "manti", "mantı", "borek", "börek", "simit", "kokorec",
            "kokoreç", "gozleme", "gözleme", "iskender", "ottoman", "anatolian", "turkish_breakfast", "cig_kofte",
            "tantuni", "soup", "home_cooking", "fish", "seafood", "baklava", "kunefe", "künefe");
    private static final Set<String> SEAFOOD_CUISINES = Set.of("fish", "seafood", "fish_and_chips");
    // Cheap by nature: street food, bakeries, tea houses (OSM cuisine values)
    private static final Set<String> BUDGET_CUISINES = Set.of("simit", "borek", "börek", "doner", "döner",
            "sandwich", "toast", "gozleme", "gözleme", "cig_kofte", "tantuni", "soup", "bagel", "pastry", "tea");

    // Words (folded, prefixes: Turkish suffixes follow) in names of Turkish / local food places
    private static final List<String> LOCAL_WORDS = List.of("kofte", "pide", "lahmacun", "kebap", "kebab", "meze",
            "evyemek", "lokanta", "ocakbasi", "manti", "corba", "gozleme", "borek", "simit", "kokorec", "iskender",
            "cigkofte", "tantuni", "doner", "kahvehane", "kiraathane", "caybahce", "cayevi", "balik", "iskembe",
            "kelle", "pace", "sofra", "kahvalti", "serpme", "kumpir", "midye", "tatlici", "baklava", "kunefe",
            "muhallebi", "dondurmaci");
    private static final List<String> SEAFOOD_WORDS = List.of("balik", "midye", "denizurun", "seafood", "fish",
            "kalamar", "levrek", "cupra", "hamsi", "istavrit");
    private static final List<String> BUDGET_WORDS = List.of("simit", "borek", "pogaca", "doner", "tost",
            "gozleme", "cigkofte", "tantuni", "corba", "lahmacun", "esnaf", "kahvehane", "kiraathane", "caybahce",
            "cayevi", "cayocag", "bufe", "kumpir", "firin", "evyemek");
    private static final List<String> BOOK_WORDS = List.of("kitap", "kitabevi", "sahaf", "book", "kutuphane", "library");
    private static final List<String> VIEW_WORDS = List.of("seyir", "manzara", "teras", "terrace", "panorama",
            "rooftop", "viewpoint");
    private static final List<String> SEA_WORDS = List.of("sahil", "plaj", "iskele", "marina", "rihtim", "kordon",
            "beach", "pier", "liman");
    private static final List<String> ART_WORDS = List.of("sanat", "galeri", "gallery", "atolye", "sergi");
    // Only for sights / museums / culture venues ("Saray Muhallebicisi" is a pudding shop, not a palace)
    private static final List<String> ARCHITECTURE_WORDS = List.of("kosk", "konak", "saray", "kule", "cami",
            "kilise", "medrese", "kervansaray", "hamam", "sinagog", "kale", "hisar", "kasr", "yali", "cesme",
            "bedesten", "kulliye", "tekke", "sebil", "surlari");
    private static final Set<String> ARCHITECTURE_HISTORIC = Set.of("castle", "fort", "palace", "mosque", "church",
            "tower", "city_gate", "aqueduct", "monastery", "caravanserai", "bath", "citywalls", "manor", "building",
            "house", "mansion", "fountain", "synagogue");
    private static final List<String> HISTORY_WORDS = List.of("tarihi", "antik", "harabe", "osmanli");
    // Not a word stem of the interest ("Balıkesir")
    private static final Set<String> FALSE_FRIENDS = Set.of("balikesir", "balikli", "balikpazari");

    private static final Set<PlaceCategory> FOOD = Set.of(PlaceCategory.BREAKFAST, PlaceCategory.RESTAURANT,
            PlaceCategory.CAFE, PlaceCategory.DESSERT);
    private static final Set<PlaceCategory> SIGHTS = Set.of(PlaceCategory.ATTRACTION, PlaceCategory.MUSEUM,
            PlaceCategory.CULTURE);

    private PlaceTags() {
    }

    /**
     * @param cuisines lowercase OSM cuisine values (empty when unknown)
     * @param osmTags  the element's OSM tags; empty for rows already in the database
     * @param existing tags the place already has (quick, bakery, tea, ...)
     * @return interest tags to add (may repeat existing ones; the caller merges)
     */
    public static List<String> derive(String name, PlaceCategory category, List<String> cuisines,
                                      Map<String, String> osmTags, List<String> existing) {
        Set<String> tags = new LinkedHashSet<>();
        List<String> words = PlaceRealismFilter.words(name == null ? "" : name).stream()
                .filter(w -> !FALSE_FRIENDS.contains(w)).toList();
        // Multi-word stems ("ev yemekleri", "çay bahçesi", "deniz ürünleri") are found in the name without spaces
        String joined = String.join("", words);
        Map<String, String> osm = osmTags == null ? Map.of() : osmTags;
        List<String> cuisine = cuisines == null ? List.of() : cuisines;
        List<String> have = existing == null ? List.of() : existing;
        boolean food = FOOD.contains(category);

        if (food) {
            if (cuisine.stream().anyMatch(LOCAL_CUISINES::contains) || anyPrefix(words, joined, LOCAL_WORDS)) {
                tags.add("local");
            }
            if (cuisine.stream().anyMatch(SEAFOOD_CUISINES::contains) || anyPrefix(words, joined, SEAFOOD_WORDS)) {
                tags.add("seafood");
            }
            if (have.contains("quick") || have.contains("bakery") || have.contains("tea")
                    || cuisine.stream().anyMatch(BUDGET_CUISINES::contains) || anyPrefix(words, joined, BUDGET_WORDS)
                    || isMunicipalFacility(words)) {
                tags.add("budget");
            }
        }
        if (anyPrefix(words, joined, BOOK_WORDS) || "books".equals(osm.get("shop"))) {
            tags.add("books");
        }
        if (anyPrefix(words, joined, VIEW_WORDS) || "viewpoint".equals(osm.get("tourism"))) {
            tags.add("view");
        }
        if (anyPrefix(words, joined, SEA_WORDS) && (category == PlaceCategory.PARK || category == PlaceCategory.ATTRACTION
                || food)) {
            tags.add("sea");
        }
        if ("beach".equals(osm.get("natural")) || "aquarium".equals(osm.get("tourism"))
                || "lighthouse".equals(osm.get("man_made"))) {
            tags.add("sea");
        }
        if (category == PlaceCategory.PARK || "nature_reserve".equals(osm.get("leisure"))
                || "garden".equals(osm.get("leisure")) || "beach".equals(osm.get("natural"))) {
            tags.add("nature");
        }
        if (category == PlaceCategory.CULTURE || "gallery".equals(osm.get("tourism"))
                || "arts_centre".equals(osm.get("amenity")) || "artwork".equals(osm.get("tourism"))
                || (SIGHTS.contains(category) && anyPrefix(words, joined, ART_WORDS))) {
            tags.add("art");
        }
        String artworkType = osm.get("artwork_type");
        if ("artwork".equals(osm.get("tourism")) && artworkType != null
                && (artworkType.contains("mural") || artworkType.contains("graffiti"))) {
            tags.add("street-art");
        }
        String historic = osm.get("historic");
        if (SIGHTS.contains(category) && ((historic != null && ARCHITECTURE_HISTORIC.contains(historic))
                || have.contains("religious") || anyPrefix(words, joined, ARCHITECTURE_WORDS))) {
            tags.add("architecture");
        }
        if (SIGHTS.contains(category) && anyPrefix(words, joined, HISTORY_WORDS)) {
            tags.add("history");
        }
        return new ArrayList<>(tags);
    }

    // Whole words only: "Eski Havra", "Ahrida Sinagogu", "CEMEVİ", "Cem Evi" - not "Havran" / "Havraniye Mah."
    private static final Pattern SYNAGOGUE_NAME = Pattern.compile("(?<!\\p{L})(sinagog\\p{L}*|synagogue|havra(s[iı])?)(?!\\p{L})");
    private static final Pattern CEMEVI_NAME = Pattern.compile("(?<!\\p{L})cem ?ev(i|leri)(?!\\p{L})");
    // Named after a cemevi / synagogue but not one: streets, squares, stops, parks, quarters, cemeteries, tombs
    private static final Pattern NOT_A_WORSHIP_PLACE = Pattern.compile("(?<!\\p{L})(sokak|sokağı|cadde|caddesi|yolu|"
            + "geçidi|aralığı|meydanı|durağı|parkı|mahallesi|mah|mezarlığı|mezarlık|türbe|türbesi|yemekhanesi|ek bina)(?!\\p{L})");

    /**
     * A cemevi or synagogue mapped without amenity=place_of_worship, known only by its name
     * ("Bağcılar Cemevi", "Bergama Yabets Sinagogu"); "Cemevi Sokağı", "Havran" and "Musevi Mezarlığı" are not.
     */
    public static boolean namedCemeviOrSynagogue(String name) {
        String lower = lowerTr(name);
        return (CEMEVI_NAME.matcher(lower).find() || SYNAGOGUE_NAME.matcher(lower).find()) && !notAWorshipPlace(name);
    }

    // Streets, cemeteries, tombs ... that only carry a religion / denomination or a cemevi / synagogue name
    public static boolean notAWorshipPlace(String name) {
        return NOT_A_WORSHIP_PLACE.matcher(lowerTr(name)).find();
    }

    private static String lowerTr(String name) {
        return name == null ? "" : name.replace('İ', 'i').replace('I', 'ı').toLowerCase(Locale.ROOT);
    }

    /**
     * The kind of a place of worship for Explore > İbadet: "mosque" (cami and mescit together), "church", "synagogue"
     * or "cemevi". The name decides first ("... Camii", "... Kilisesi", "Cemevi"), then the religion
     * (OSM religion=muslim / christian / jewish, Overture muslim_ / christian_ / jewish_place_of_worship); null = unknown.
     */
    public static String worshipKind(String name, String religion) {
        return worshipKind(name, religion, null);
    }

    // denomination: OSM denomination=alevi / bektashi is a cemevi even when religion says muslim
    public static String worshipKind(String name, String religion, String denomination) {
        String folded = OsmPlaceMapper.fold(name == null ? "" : name);
        String d = denomination == null ? "" : denomination.toLowerCase(java.util.Locale.ROOT);
        if (folded.contains("cemevi") || folded.contains("cemevleri") || d.contains("alevi") || d.contains("bektashi")) {
            return "cemevi";
        }
        if (folded.contains("cami") || folded.contains("mescid") || folded.contains("mescit") || folded.contains("mosque")) {
            return "mosque";
        }
        if (folded.contains("kilise") || folded.contains("church") || folded.contains("katedral")
                || folded.contains("sapel") || folded.contains("chapel") || folded.contains("manastir")) {
            return "church";
        }
        if (SYNAGOGUE_NAME.matcher(lowerTr(name)).find()) {
            return "synagogue";
        }
        String r = religion == null ? "" : religion.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("muslim")) {
            return "mosque";
        }
        if (r.contains("christian")) {
            return "church";
        }
        if (r.contains("jewish")) {
            return "synagogue";
        }
        return null;
    }

    // Tags plus derived ones, each once, in a stable order
    public static List<String> merge(List<String> tags, List<String> derived) {
        Set<String> all = new LinkedHashSet<>(tags == null ? List.of() : tags);
        all.addAll(derived);
        return new ArrayList<>(all);
    }

    // "Belediye Sosyal Tesisleri", "İBB ... Sosyal Tesisi": public and cheap
    static boolean isMunicipalFacility(List<String> words) {
        boolean facility = false;
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equals("sosyal") && words.get(i + 1).startsWith("tesis")) {
                facility = true;
                break;
            }
        }
        return facility && words.stream().anyMatch(w -> w.startsWith("belediye") || w.equals("ibb")
                || w.startsWith("buyuksehir") || w.startsWith("beltur"));
    }

    private static boolean anyPrefix(List<String> words, String joined, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (prefix.length() >= 6 && joined.contains(prefix)) {
                return true;
            }
        }
        for (String word : words) {
            for (String prefix : prefixes) {
                if (word.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}

package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Drops OSM entries that are not places a visitor can actually go to: a school canteen, a faculty dining hall,
 * a staff canteen, a military / police club, a dormitory café, a closed place, a private garden, or an entry whose
 * "name" is only a generic word ("Kafe", "Park", "Büfe") or a lowercase description ("okul kantini").
 *
 * Two levels:
 * - {@link #rejectElement}: the OSM tags (access=private/no, disused / abandoned, opening_hours=off/closed, the
 *   same kind also under a disused:/abandoned:/was: key) plus the name rules. Used by the import.
 * - {@link #rejectName}: the name rules only (category-aware). Used for rows already in the database, whose OSM
 *   tags we do not store (PlaceRealismCleanup).
 *
 * Names are compared word by word after folding (Turkish letters, Locale.ROOT, no punctuation), so "Kantini",
 * "KANTİN" and "kantin" match and "Yurttaş" is not "yurt". The rules are deliberately category-aware: a park named
 * after a police officer ("Şehit Polis Fuat Bal Parkı") or in the "Yurt" neighbourhood is a real public park, a
 * "Cezaevi Müzesi" is a museum, but a café called "Polis Evi" or "76. Yurt Kantini" is not open to visitors.
 * Municipal social facilities ("Belediye Sosyal Tesisleri", "İBB ... Sosyal Tesisleri") are public cafés and are
 * kept; those of an institution ("Emniyet Müdürlüğü ... Sosyal Tesisleri") are not.
 * Pure logic, unit tested with real names from the Istanbul / Ankara imports.
 */
public final class PlaceRealismFilter {

    private static final Set<PlaceCategory> FOOD = Set.of(PlaceCategory.BREAKFAST, PlaceCategory.RESTAURANT,
            PlaceCategory.CAFE, PlaceCategory.DESSERT);

    // A word starting with one of these makes a food / drink place, park or venue an institution's own facility
    private static final List<String> INSTITUTION_STEMS = List.of("kantin", "yemekhane", "lojman", "misafirhane",
            "orduev", "askeri", "personel", "ogrenci", "cezaev", "santiye", "yatakhane");
    // Schools: never a café / restaurant / park for visitors ("Saray İlkokulu", "fen lisesi parkı", "Anaokulu")
    private static final List<String> SCHOOL_STEMS = List.of("okul", "ilkokul", "ortaokul", "anaokul", "lise",
            "kres", "yuksekokul", "fakulte");
    // Food / drink only: hospital canteens, police / gendarmerie clubs, dormitories
    private static final List<String> FOOD_ONLY_STEMS = List.of("hastane", "jandarma", "polisevi");
    private static final Set<String> FOOD_ONLY_WORDS = Set.of("yurt", "yurdu", "yurtlari", "yurdunun", "kyk");
    // "Sosyal Tesisleri" of an institution (not a municipality)
    private static final List<String> INSTITUTION_MARKERS = List.of("mudurlug", "mudurluk", "emniyet", "polis",
            "jandarma", "askeri", "ordu", "personel", "kurum", "sirket", "holding", "fabrika", "tcdd", "ptt", "botas",
            "tedas", "teias", "dsi", "karayollari", "hastane", "universite", "banka", "enerji", "elektrik");
    private static final List<String> MUNICIPAL_MARKERS = List.of("belediye", "ibb", "buyuksehir", "beltur");

    // Whole (folded) names that are only a kind of place, not a name
    private static final Set<String> GENERIC_NAMES = Set.of(
            "kafe", "cafe", "kahve", "kahvehane", "kiraathane", "kiraathanesi", "cayevi", "caybahcesi", "cayocagi",
            "bufe", "kantin", "yemekhane", "restoran", "restaurant", "lokanta", "pastane", "pastahane", "tatlici",
            "dondurma", "dondurmaci", "firin", "ekmekfirini", "simitci", "borekci", "doner", "donerci", "kebap",
            "kebapci", "kofteci", "pideci", "lahmacun", "fastfood", "kafeterya", "cafeteria", "cafebar", "bar",
            "coffee", "coffeeshop", "bistro", "park", "parki", "cocukparki", "oyunparki", "oyunalani",
            "cocukoyunalani", "bahce", "mesirealani", "piknikalani", "seyirterasi", "seyirtepesi", "manzara",
            "viewpoint", "muze", "museum", "cami", "camii", "mescit", "mescidi", "kilise", "church", "mosque", "plaj",
            "beach", "halkplaji", "pazar", "pazaryeri", "semtpazari", "halkpazari", "marketplace", "carsi", "anit",
            "monument", "kale", "hamam", "cesme", "tiyatro", "galeri", "gallery", "sanatgalerisi", "market",
            "bakkal", "supermarket", "minimarket", "gida", "sinagog", "cemevi");
    // A lowercase name containing one of these words describes a place instead of naming it ("okul kantini")
    private static final List<String> DESCRIPTION_STEMS = List.of("kantin", "kafe", "cafe", "restoran", "lokanta",
            "park", "bahce", "bufe", "cay", "yemek", "okul", "lise", "fakulte", "pastane", "firin", "kahve");

    private static final Set<String> NO_ACCESS = Set.of("private", "no");
    private static final List<String> LIFECYCLE_PREFIXES = List.of("disused:", "abandoned:", "was:");
    private static final List<String> MAIN_KEYS = List.of("amenity", "shop", "tourism", "leisure");

    private PlaceRealismFilter() {
    }

    /**
     * @return why the element is not a real visitable place, or null when it is fine
     */
    public static String rejectElement(OverpassResponse.Element element, OsmPlaceMapper.OsmPlace place) {
        Map<String, String> tags = element.tags() == null ? Map.of() : element.tags();
        String access = lower(tags.get("access"));
        if (access != null && NO_ACCESS.contains(access)) {
            return "access=" + access;
        }
        if ("yes".equals(lower(tags.get("disused"))) || "yes".equals(lower(tags.get("abandoned")))) {
            return "disused";
        }
        String hours = lower(tags.get("opening_hours"));
        if ("off".equals(hours) || "closed".equals(hours)) {
            return "opening_hours=" + hours;
        }
        // amenity=cafe + disused:amenity=cafe: mappers often keep the old tag when a place closes
        for (String key : MAIN_KEYS) {
            String value = tags.get(key);
            if (value == null) {
                continue;
            }
            for (String prefix : LIFECYCLE_PREFIXES) {
                if (value.equals(tags.get(prefix + key))) {
                    return prefix + key;
                }
            }
        }
        // "Gezi Parkı olaylarının gerçekleştiği yer": a description of an event, not a place (whatever else it has)
        if (PlaceNames.describesEventOrSentence(place.name())) {
            return "describes an event / a sentence";
        }
        // A public business (website, phone, brand, opening hours, Wikidata item) is real even when its name is a
        // generic or institution word: the restaurant "Kantin" in Nişantaşı. A bare "okul kantini" has none of these
        if (hasPublicBusinessSigns(tags)) {
            return null;
        }
        // The name as mapped in OSM: the place name was already title-cased (PlaceNames), which would hide a lowercase
        // description such as "okul kantini"
        String raw = tags.get("name:tr") != null && !tags.get("name:tr").isBlank() ? tags.get("name:tr")
                : tags.get("name") != null && !tags.get("name").isBlank() ? tags.get("name") : place.name();
        return rejectName(raw.strip(), place.category());
    }

    private static final List<String> BUSINESS_KEYS = List.of("website", "contact:website", "phone", "contact:phone",
            "brand", "brand:wikidata", "opening_hours", "wikidata");

    // opening_hours=off / closed was rejected before; any other value counts
    static boolean hasPublicBusinessSigns(Map<String, String> tags) {
        return BUSINESS_KEYS.stream().anyMatch(key -> {
            String value = tags.get(key);
            return value != null && !value.isBlank();
        });
    }

    /**
     * @return why a place of this name and category is not a real visitable place, or null when it is fine
     */
    public static String rejectName(String name, PlaceCategory category) {
        if (name == null || name.isBlank()) {
            return "no name";
        }
        String folded = OsmPlaceMapper.fold(name);
        if (folded.isEmpty() || folded.chars().allMatch(Character::isDigit) || GENERIC_NAMES.contains(folded)) {
            return "generic name";
        }
        if (PlaceNames.describesEventOrSentence(name)) {
            return "describes an event / a sentence";
        }
        List<String> words = words(name);

        if (isLowercaseDescription(name, words)) {
            return "description, not a name";
        }

        boolean food = FOOD.contains(category);
        boolean historicOrMuseum = category == PlaceCategory.MUSEUM || category == PlaceCategory.ATTRACTION;
        if (!historicOrMuseum) {
            String institution = firstStem(words, INSTITUTION_STEMS);
            if (institution != null) {
                return "institution (" + institution + ")";
            }
            // Not for culture venues: a former school can be an art space ("Galata Rum Okulu")
            if (food || category == PlaceCategory.PARK) {
                String school = firstStem(words, SCHOOL_STEMS);
                if (school != null) {
                    return "school (" + school + ")";
                }
            }
        }
        if (food) {
            String staff = firstStem(words, FOOD_ONLY_STEMS);
            if (staff != null) {
                return "institution (" + staff + ")";
            }
            if (words.stream().anyMatch(FOOD_ONLY_WORDS::contains)) {
                return "institution (yurt)";
            }
            // "Polis Evi", "Polis Misafirhanesi"
            for (int i = 0; i + 1 < words.size(); i++) {
                if (words.get(i).equals("polis") && words.get(i + 1).startsWith("ev")) {
                    return "institution (polis evi)";
                }
            }
        }
        if (category == PlaceCategory.PARK && firstStem(words, List.of("hastane")) != null
                && !words.contains("onu")) {
            return "institution (hastane)";
        }
        if (isInstitutionFacility(words)) {
            return "institution (sosyal tesis)";
        }
        return null;
    }

    // "... Sosyal Tesisleri" of a directorate, company, bank or the police; municipal ones are public
    static boolean isInstitutionFacility(List<String> words) {
        boolean facility = false;
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equals("sosyal") && words.get(i + 1).startsWith("tesis")) {
                facility = true;
                break;
            }
        }
        if (!facility || firstStem(words, MUNICIPAL_MARKERS) != null) {
            return false;
        }
        return firstStem(words, INSTITUTION_MARKERS) != null;
    }

    // All lowercase (no capital letter at all), at most 3 words, and it names a kind of place: "okul kantini"
    static boolean isLowercaseDescription(String name, List<String> words) {
        boolean hasLetter = name.chars().anyMatch(Character::isLetter);
        boolean hasUpper = name.chars().anyMatch(Character::isUpperCase);
        return hasLetter && !hasUpper && words.size() <= 3 && firstStem(words, DESCRIPTION_STEMS) != null;
    }

    private static String firstStem(List<String> words, List<String> stems) {
        for (String word : words) {
            for (String stem : stems) {
                if (word.startsWith(stem)) {
                    return stem;
                }
            }
        }
        return null;
    }

    // Folded words: "Tıp Fakültesi kantini" -> [tip, fakultesi, kantini]
    static List<String> words(String name) {
        return Arrays.stream(name.split("[^\\p{L}\\p{N}]+"))
                .map(OsmPlaceMapper::fold)
                .filter(w -> !w.isEmpty())
                .toList();
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}

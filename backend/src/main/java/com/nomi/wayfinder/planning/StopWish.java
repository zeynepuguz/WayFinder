package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.i18n.TurkishFold;
import com.nomi.wayfinder.osm.OsmPlaceMapper;

import java.util.*;

/**
 * What the user wants instead of a stop ("Başka bir yerle değiştir" > "neye göre?"): free text such as "kebap",
 * "daha ucuz", "daha yakın", "deniz manzaralı", "kapalı bir yer". Read with plain rules (no guessing): price / distance
 * / indoor words, interest words that map to place tags, and the remaining words, which are looked for in the
 * place's name, cuisine and tags ("kebap", "pilav", "vegan"). Pure logic, unit tested.
 *
 * @param text       the user's own words (shown in notes)
 * @param words      folded food / place words to find in name, cuisine or tags ("kebap", "kebab")
 * @param tags       place tags wanted (any of them): "sea", "view", "history", ...
 * @param cheaper    "daha ucuz": a known lower price than the replaced place, or a budget-friendly place
 * @param closer     "daha yakın": the nearest fitting place wins, not the best scored one
 * @param indoor       "kapalı alan" = TRUE, "açık hava / bahçe" = FALSE, null = either
 * @param replacedCost the replaced place's price per person (null = unknown), for "daha ucuz"
 */
public record StopWish(String text, List<String> words, Set<String> tags, boolean cheaper, boolean closer,
                       Boolean indoor, Integer replacedCost) {

    private static final List<String> CHEAPER = List.of("ucuz", "uygun", "ekonomik", "hesapli", "cheap", "budget",
            "inexpensive", "affordable");
    private static final List<String> CLOSER = List.of("yakin", "yuruyus", "yurume", "yurumeyelim", "closer",
            "nearer", "near", "nearby");
    private static final List<String> INDOOR = List.of("kapali", "icmekan", "indoor", "inside");
    private static final List<String> OUTDOOR = List.of("acikhava", "acikalan", "bahce", "bahceli", "teras",
            "terasli", "outdoor", "outside", "garden", "terrace");
    // Folded word prefixes -> place tags (service/Interests vocabulary)
    private static final Map<String, String> TAG_WORDS = new LinkedHashMap<>();
    // Same food in other spellings: "kebap" also finds "Kebab House"
    private static final Map<String, List<String>> SPELLINGS = Map.of(
            "kebap", List.of("kebab"),
            "kebab", List.of("kebap"),
            "kahve", List.of("coffee"),
            "balik", List.of("fish", "seafood"),
            "vejetaryen", List.of("vegetarian", "vegan"),
            "vegan", List.of("vegetarian"),
            "tatli", List.of("dessert"),
            "pizza", List.of("pizzeria"),
            "burger", List.of("hamburger"));
    // Words that say nothing about the place
    private static final Set<String> FILLER = Set.of("daha", "bir", "yer", "yere", "yerde", "mekan", "mekani",
            "olsun", "olan", "istiyorum", "isterim", "istiyoruz", "lutfen", "gibi", "icin", "ile", "biraz", "cok", "en",
            "bana", "bize", "yerine", "baska", "seyler", "sey", "bu", "su", "o", "ve", "veya", "ya", "da", "de",
            "olabilir", "mi", "mu", "var", "yok", "iyi", "guzel", "tercih", "ederim", "something", "place", "more",
            "with", "want", "the", "and", "please", "some", "somewhere", "better", "less", "for", "nice", "good",
            "fiyat", "fiyatli", "fiyatlari", "price", "prices", "alan", "hava", "mesafe", "uzak", "far",
            "kenari", "kenarinda", "lezzetli", "tasty");

    static {
        TAG_WORDS.put("deniz", "sea");
        TAG_WORDS.put("sahil", "sea");
        TAG_WORDS.put("sea", "sea");
        TAG_WORDS.put("manzara", "view");
        TAG_WORDS.put("view", "view");
        TAG_WORDS.put("tarih", "history");
        TAG_WORDS.put("histor", "history");
        TAG_WORDS.put("muze", "museum");
        TAG_WORDS.put("museum", "museum");
        TAG_WORDS.put("doga", "nature");
        TAG_WORDS.put("park", "nature");
        TAG_WORDS.put("nature", "nature");
        TAG_WORDS.put("sanat", "art");
        TAG_WORDS.put("art", "art");
        TAG_WORDS.put("yerel", "local");
        TAG_WORDS.put("local", "local");
        TAG_WORDS.put("geleneksel", "traditional");
        TAG_WORDS.put("traditional", "traditional");
        TAG_WORDS.put("mimari", "architecture");
        TAG_WORDS.put("cami", "religious");
        TAG_WORDS.put("kitap", "books");
        TAG_WORDS.put("sahaf", "books");
        TAG_WORDS.put("hizli", "quick");
        TAG_WORDS.put("quick", "quick");
    }

    /**
     * @return null for blank text
     */
    public static StopWish parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = TurkishFold.lower(text);
        List<String> folded = Arrays.stream(lower.split("[^\\p{L}\\p{N}]+"))
                .map(OsmPlaceMapper::fold)
                .filter(w -> !w.isEmpty())
                .toList();
        String joined = String.join("", folded);

        boolean cheaper = folded.stream().anyMatch(w -> CHEAPER.stream().anyMatch(w::startsWith));
        boolean closer = folded.stream().anyMatch(w -> CLOSER.stream().anyMatch(w::startsWith));
        Boolean indoor = OUTDOOR.stream().anyMatch(joined::contains) ? Boolean.FALSE
                : INDOOR.stream().anyMatch(joined::contains) ? Boolean.TRUE : null;

        Set<String> tags = new LinkedHashSet<>();
        List<String> words = new ArrayList<>();
        for (String w : folded) {
            if (FILLER.contains(w) || w.length() < 3
                    || CHEAPER.stream().anyMatch(w::startsWith) || CLOSER.stream().anyMatch(w::startsWith)
                    || INDOOR.stream().anyMatch(w::startsWith) || OUTDOOR.stream().anyMatch(w::startsWith)
                    || w.equals("acik")) {
                continue;
            }
            Optional<String> tag = TAG_WORDS.entrySet().stream()
                    .filter(e -> w.startsWith(e.getKey())).map(Map.Entry::getValue).findFirst();
            if (tag.isPresent()) {
                tags.add(tag.get());
                continue;
            }
            String stem = stem(w);
            if (!words.contains(stem)) {
                words.add(stem);
                SPELLINGS.getOrDefault(stem, List.of()).forEach(s -> {
                    if (!words.contains(s)) {
                        words.add(s);
                    }
                });
            }
        }
        return new StopWish(text.trim(), List.copyOf(words), Set.copyOf(tags), cheaper, closer, indoor, null);
    }

    public StopWish replacing(Integer cost) {
        return new StopWish(text, words, tags, cheaper, closer, indoor, cost);
    }

    // "kebapçı" -> "kebap", "pideli" -> "pide", "balıkçı" -> "balik": Turkish suffixes off the end
    static String stem(String folded) {
        for (String suffix : List.of("cilar", "ciler", "cisi", "cusu", "ci", "cu", "li", "lu", "lar", "ler", "si", "su")) {
            if (folded.endsWith(suffix) && folded.length() - suffix.length() >= 4) {
                return folded.substring(0, folded.length() - suffix.length());
            }
        }
        return folded;
    }

    // Anything to filter by (otherwise the stop is simply swapped for the next best place)
    public boolean hasCriteria() {
        return !words.isEmpty() || !tags.isEmpty() || cheaper || indoor != null;
    }

    /**
     * Does the place fit what was asked? Every stated wish must hold: one of the words, one of the tags,
     * the price and indoor / outdoor. "Daha yakın" is no filter (see closer).
     */
    public boolean matches(Place place) {
        if (!words.isEmpty()) {
            String haystack = OsmPlaceMapper.fold(place.getName()) + " " + OsmPlaceMapper.fold(place.getCuisine())
                    + " " + String.join(" ", place.getTags());
            if (words.stream().noneMatch(haystack::contains)) {
                return false;
            }
        }
        if (!tags.isEmpty() && tags.stream().noneMatch(t -> place.hasTag(t) || "sea".equals(t) && place.isNearSea())) {
            return false;
        }
        if (indoor != null && place.isIndoor() != indoor) {
            return false;
        }
        if (cheaper) {
            Integer cost = place.getEstimatedCost();
            boolean lower = cost != null && replacedCost != null && cost < replacedCost;
            boolean budget = place.hasTag("budget") || place.hasTag("quick");
            return lower || budget;
        }
        return true;
    }

    // LIKE patterns for the name / cuisine search ("%kebap%"); empty when only tags / price / distance were asked
    public String likePatterns() {
        return String.join(",", words.stream().map(w -> "%" + w + "%").toList());
    }
}

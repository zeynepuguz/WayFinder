package com.nomi.wayfinder.area;

import com.nomi.wayfinder.i18n.TurkishFold;

import java.util.*;

/**
 * Finds the city, district or neighbourhood a message names: "üsküdarda gezeceğiz", "Kadıköy'e gidiyoruz",
 * "Moda'da kahve", "bebekte", "a day in Kadikoy", "Ankara'da", "İzmir Konak'ta". Pure logic over a fixed list
 * (AreaResolver loads it: the 81 cities and the districts / neighbourhoods of every imported city).
 *
 * Both sides are ASCII-folded the Turkish way. A name matches consecutive message words: every word but the last
 * exactly, the last one as a prefix followed by a Turkish case ending ("'da", "'dan", "e", "deyim", ...), with
 * or without an apostrophe. Only real endings count, so "Şile" does not match "silebilir".
 * Names are indexed by their first word, so a message only looks at the names its words can start.
 *
 * Which one wins:
 * - a name used in several cities (districts / neighbourhoods like "Yenişehir") only counts in a city the message
 *   also names ("İzmir Konak'ta"), else in the user's current city, else not at all; a name used in one city
 *   only counts wherever the user is
 * - when the message names a city, a district / neighbourhood of that city wins over it; otherwise the city itself
 *   (its label point) is the answer
 * - longest name wins ("Kuzguncuk" over "Kuzgun"), then a place in the user's current city, then a district
 *   over a neighbourhood
 *
 * Left out on purpose: names shorter than MIN_NAME_LETTERS (cities: MIN_CITY_LETTERS, "Van", "Muş"), common words
 * that are also district / neighbourhood names ("Merkez", "Cumhuriyet") and neighbourhood names used for several
 * places far apart inside one city (which one would it be?).
 */
public final class AreaMatcher {

    static final int MIN_NAME_LETTERS = 4;
    static final int MIN_CITY_LETTERS = 3;
    // The same neighbourhood name further apart than this means different places
    static final double AMBIGUOUS_METERS = 1500;

    // Folded Turkish case / copula endings that may follow a place name (also English "'s"). Not "-le" / "-li"
    // ("bebekle", "bebekli" = with a baby, not in Bebek)
    static final Set<String> SUFFIXES = Set.of(
            "", "a", "e", "i", "u", "ya", "ye", "yi", "yu", "na", "ne", "nin", "nun", "in", "un",
            "da", "de", "ta", "te", "dan", "den", "tan", "ten", "nda", "nde", "ndan", "nden",
            "daki", "deki", "taki", "teki", "ndaki", "ndeki",
            "dayim", "deyim", "tayim", "teyim", "dayiz", "deyiz", "tayiz", "teyiz",
            "dir", "dur", "tir", "tur", "s");

    // District / neighbourhood names that are everyday words (city names are always kept)
    static final Set<String> COMMON_WORDS = Set.of(
            "merkez", "cumhuriyet", "yeni", "eski", "carsi", "sahil", "iskele", "istasyon", "liman", "kale",
            "pazar", "tepe", "koy", "mahalle", "cami", "camii", "orman", "park", "bahce", "sanayi", "zafer",
            "baris", "gazi", "yildiz", "guzel", "cicek", "gunes", "deniz", "istiklal", "ataturk", "hurriyet",
            "saray", "koru", "bagla", "kavak", "cinar", "sokak", "cadde", "meydan", "kent", "site", "evler",
            "istanbul", "turkiye");

    // First word of a name -> the names starting with it
    private final Map<String, List<Entry>> byFirstWord;
    private final int size;

    public AreaMatcher(List<NamedArea> areas) {
        List<Entry> entries = usable(areas);
        this.size = entries.size();
        Map<String, List<Entry>> index = new HashMap<>();
        for (Entry entry : entries) {
            index.computeIfAbsent(entry.tokens()[0], k -> new ArrayList<>()).add(entry);
        }
        this.byFirstWord = index;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    // The area named in free text (a chat message, or the AI service's "area" field); current city unknown
    public Optional<NamedArea> find(String text) {
        return find(text, null);
    }

    /**
     * @param currentCity the city the user is in (NamedArea#city spelling, e.g. "İstanbul"); null = unknown
     */
    public Optional<NamedArea> find(String text, String currentCity) {
        if (text == null || text.isBlank() || byFirstWord.isEmpty()) {
            return Optional.empty();
        }
        List<String> tokens = tokens(text);

        // Every name the message contains, grouped by name ("konak" -> the Konak districts of all cities)
        Map<String, List<Entry>> found = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            for (String stem : stems(tokens.get(i))) {
                for (Entry entry : byFirstWord.getOrDefault(stem, List.of())) {
                    if (matchesAt(tokens, i, entry.tokens())) {
                        List<Entry> same = found.computeIfAbsent(entry.key(), k -> new ArrayList<>());
                        if (!same.contains(entry)) {
                            same.add(entry);
                        }
                    }
                }
            }
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }

        // Cities the message names ("Ankara'da", "İzmir Konak'ta")
        Set<String> namedCities = new HashSet<>();
        Entry namedCity = null;
        for (List<Entry> same : found.values()) {
            for (Entry entry : same) {
                if (entry.area().kind() == NamedArea.Kind.CITY) {
                    namedCities.add(cityKey(entry.area().city()));
                    if (namedCity == null || entry.letters() > namedCity.letters()) {
                        namedCity = entry;
                    }
                }
            }
        }
        String current = currentCity == null ? null : cityKey(currentCity);

        Entry best = null;
        for (List<Entry> same : found.values()) {
            if (same.stream().anyMatch(e -> e.area().kind() == NamedArea.Kind.CITY)) {
                // A city's name means the city, even if a district / neighbourhood somewhere has it too
                continue;
            }
            List<Entry> candidates = inContext(same, namedCities, current);
            for (Entry entry : candidates) {
                if (!namedCities.isEmpty() && !namedCities.contains(cityKey(entry.area().city()))) {
                    // The message names a city: only its own districts / neighbourhoods refine it
                    continue;
                }
                if (best == null || better(entry, best, current)) {
                    best = entry;
                }
            }
        }
        if (best != null) {
            return Optional.of(best.area());
        }
        return Optional.ofNullable(namedCity).map(Entry::area);
    }

    /**
     * Entries of one name that count here: all of them when they are in one city (or the city is unknown),
     * else those in a city the message names, else those in the user's current city, else none (ambiguous).
     */
    private static List<Entry> inContext(List<Entry> same, Set<String> namedCities, String currentCity) {
        Set<String> cities = new HashSet<>();
        same.forEach(e -> cities.add(cityKey(e.area().city())));
        if (cities.size() <= 1) {
            return same;
        }
        List<Entry> inNamed = same.stream().filter(e -> namedCities.contains(cityKey(e.area().city()))).toList();
        if (!inNamed.isEmpty()) {
            return inNamed;
        }
        if (currentCity != null) {
            return same.stream().filter(e -> currentCity.equals(cityKey(e.area().city()))).toList();
        }
        return List.of();
    }

    private static boolean better(Entry candidate, Entry current, String currentCity) {
        if (candidate.letters() != current.letters()) {
            return candidate.letters() > current.letters();
        }
        if (currentCity != null) {
            boolean candidateHere = currentCity.equals(cityKey(candidate.area().city()));
            boolean currentHere = currentCity.equals(cityKey(current.area().city()));
            if (candidateHere != currentHere) {
                return candidateHere;
            }
        }
        return candidate.area().kind() == NamedArea.Kind.DISTRICT && current.area().kind() != NamedArea.Kind.DISTRICT;
    }

    static boolean matches(List<String> message, String[] name) {
        for (int i = 0; i + name.length <= message.size(); i++) {
            if (matchesAt(message, i, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(List<String> message, int i, String[] name) {
        int k = name.length;
        if (i + k > message.size()) {
            return false;
        }
        for (int j = 0; j < k - 1; j++) {
            if (!message.get(i + j).equals(name[j])) {
                return false;
            }
        }
        return withSuffix(message.get(i + k - 1), name[k - 1]);
    }

    private static boolean withSuffix(String token, String name) {
        if (!token.startsWith(name)) {
            return false;
        }
        String rest = token.substring(name.length());
        if (rest.startsWith("'")) {
            rest = rest.substring(1);
        }
        return SUFFIXES.contains(rest);
    }

    // What a message word can start with: the word itself, or the word without one case ending ("kadikoy'de" -> "kadikoy")
    private static Set<String> stems(String token) {
        Set<String> stems = new HashSet<>();
        for (String suffix : SUFFIXES) {
            if (token.endsWith(suffix)) {
                String stem = token.substring(0, token.length() - suffix.length());
                if (stem.endsWith("'")) {
                    stem = stem.substring(0, stem.length() - 1);
                }
                if (!stem.isEmpty()) {
                    stems.add(stem);
                }
            }
        }
        return stems;
    }

    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String t : TurkishFold.ascii(text).split("[^a-z0-9']+")) {
            String trimmed = t.replaceAll("^'+|'+$", "");
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    private static List<Entry> usable(List<NamedArea> areas) {
        // Neighbourhood names that point at several places far apart inside one city
        Map<String, List<NamedArea>> byCityAndName = new HashMap<>();
        for (NamedArea area : areas) {
            if (area.kind() == NamedArea.Kind.AREA) {
                byCityAndName.computeIfAbsent(cityKey(area.city()) + "|" + key(area.name()), k -> new ArrayList<>())
                        .add(area);
            }
        }

        List<Entry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (NamedArea area : areas) {
            String key = key(area.name());
            if (key.isEmpty()) {
                continue;
            }
            int letters = key.replace(" ", "").length();
            boolean city = area.kind() == NamedArea.Kind.CITY;
            if (letters < (city ? MIN_CITY_LETTERS : MIN_NAME_LETTERS)) {
                continue;
            }
            if (!city && COMMON_WORDS.contains(key.replace(" ", ""))) {
                continue;
            }
            if (area.kind() == NamedArea.Kind.AREA) {
                String cityAndName = cityKey(area.city()) + "|" + key;
                if (isAmbiguous(byCityAndName.get(cityAndName)) || !seen.add(cityAndName)) {
                    continue;
                }
            }
            result.add(new Entry(area, key.split(" "), letters, key));
        }
        return result;
    }

    private static boolean isAmbiguous(List<NamedArea> sameName) {
        if (sameName == null || sameName.size() < 2) {
            return false;
        }
        NamedArea first = sameName.getFirst();
        return sameName.stream().anyMatch(a -> distanceMeters(first.latitude(), first.longitude(),
                a.latitude(), a.longitude()) > AMBIGUOUS_METERS);
    }

    // "Kuzguncuk" -> "kuzguncuk", "Bahçeşehir 2. Kısım" -> "bahcesehir 2 kisim"
    static String key(String name) {
        return String.join(" ", Arrays.stream(TurkishFold.ascii(name).split("[^a-z0-9]+"))
                .filter(s -> !s.isEmpty()).toList());
    }

    // Cities are compared folded ("İzmir" = "izmir"); "" = unknown city
    private static String cityKey(String city) {
        return city == null ? "" : key(city);
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
    }

    private record Entry(NamedArea area, String[] tokens, int letters, String key) {
    }
}

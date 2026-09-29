package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.osm.OsmPlaceMapper;
import com.nomi.wayfinder.overture.OvertureMapper.OverturePlace;

import java.util.*;

/**
 * Conflates Overture food places with the places we already have (OSM / verified), in one area:
 * - an Overture place with the same name (one contains the other, generic words like "cafe" / "restoran" left out)
 *   within MATCH_METERS confirms that place: it exists today. The highest-confidence Overture place wins.
 * - an Overture place that matches nothing and is certain enough (minConfidence) is a new place; copies of one place
 *   (Meta often has two pages) within SAME_PLACE_METERS are kept once, the most certain.
 * Places are bucketed in a grid of ~170 m cells, so a city with 100k Overture rows takes well under a second.
 * Pure logic, unit tested.
 */
public final class OvertureMatcher {

    static final double MATCH_METERS = 120;
    static final double SAME_PLACE_METERS = 100;
    // Two Overture pages with one phone this close are one business ("Ali Ustanın Yeri" / "05 Ali Usta İşkembe")
    static final double SAME_PHONE_METERS = 150;
    // A core name shorter than this ("Et", "Cafe 1") only matches exactly
    static final int MIN_CONTAINED_LENGTH = 4;
    private static final double CELL_LAT = 0.0015;
    private static final double CELL_LON = 0.002;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    // Words that say what kind of place it is, not which one ("Kelebek Cafe" = "Kelebek Kafe ve Restaurant")
    private static final Set<String> GENERIC_WORDS = Set.of("cafe", "kafe", "cafeteria", "kafeterya", "restaurant",
            "restoran", "restorant", "lokanta", "lokantasi", "lokantalari", "ve", "and", "the", "bistro", "coffee",
            "kahve", "kahvesi", "evi", "salonu", "pastanesi", "pastane", "fastfood", "yemek", "yemekleri", "et",
            "kebap", "kebab", "doner", "unlu", "mamulleri", "firini", "firin", "cafeandrestaurant",
            // Food words and name fillers many different places share ("Tavuk Dünyası" is not "Tavukçu Ali")
            "usta", "ustanin", "ustadan", "yeri", "sofrasi", "sofra", "tavuk", "pide", "pizza", "burger",
            "kofte", "cig", "cigkofte", "dondurma", "borek", "simit", "balik", "mangal", "izgara", "cay",
            "bahcesi", "ocakbasi", "kahvalti", "kahvaltici", "dunyasi", "lezzet", "lezzetleri", "mutfak",
            "mutfagi", "gurme", "tatli", "tatlicisi", "salon", "house", "bar");
    // Distinct words this long are the name ("Zeze"); one typo is forgiven from TYPO_LENGTH letters ("Izzgara")
    static final int MIN_TOKEN_LENGTH = 4;
    static final int TYPO_LENGTH = 5;
    static final int MIN_FULL_NAME_LENGTH = 8;

    private OvertureMatcher() {
    }

    /**
     * A place we already have near the area.
     *
     * @param source OSM, WEB_CHECK, ... (OVERTURE rows are not passed: they are refreshed by their own id)
     */
    public record ExistingPlace(long id, String name, double latitude, double longitude, String source) {
    }

    /**
     * @param confirmed existing place id -> the Overture place that confirms it
     * @param added     Overture places to add as new places, most certain first
     */
    public record Result(Map<Long, OverturePlace> confirmed, List<OverturePlace> added) {
    }

    public static Result match(List<OverturePlace> overture, List<ExistingPlace> existing, double minConfidence) {
        Grid<Named<ExistingPlace>> existingGrid = new Grid<>();
        for (ExistingPlace place : existing) {
            String core = coreName(place.name());
            if (!core.isEmpty()) {
                existingGrid.add(place.latitude(), place.longitude(), new Named<>(place, core));
            }
        }

        List<OverturePlace> byConfidence = new ArrayList<>(overture);
        byConfidence.sort(Comparator.comparingDouble(OverturePlace::confidence).reversed()
                .thenComparing(OverturePlace::overtureId));

        // 1) Overture places that are one of ours confirm it (and are not added)
        Map<Long, OverturePlace> confirmed = new LinkedHashMap<>();
        Set<String> matched = new HashSet<>();
        // Phones of matched / added places: another Overture page with the same phone nearby is the same business
        Grid<OverturePlace> phones = new Grid<>();
        for (OverturePlace place : byConfidence) {
            String core = coreName(place.name());
            if (core.isEmpty()) {
                continue;
            }
            Optional<Named<ExistingPlace>> same = existingGrid.near(place.latitude(), place.longitude()).stream()
                    .filter(e -> meters(place, e.value().latitude(), e.value().longitude()) <= MATCH_METERS
                            && (sameName(core, e.core()) || sharesToken(place.name(), e.value().name())
                            || containsFullName(place.name(), e.value().name())))
                    .min(Comparator.comparingDouble(e -> meters(place, e.value().latitude(), e.value().longitude())));
            if (same.isPresent()) {
                confirmed.putIfAbsent(same.get().value().id(), place);
                matched.add(place.overtureId());
                phones.add(place.latitude(), place.longitude(), place);
            }
        }

        // 2) The others are new places when certain enough, once per place (same name or same phone nearby)
        List<OverturePlace> added = new ArrayList<>();
        Grid<Named<OverturePlace>> addedGrid = new Grid<>();
        for (OverturePlace place : byConfidence) {
            String core = coreName(place.name());
            if (core.isEmpty() || matched.contains(place.overtureId()) || place.confidence() < minConfidence) {
                continue;
            }
            boolean copy = addedGrid.near(place.latitude(), place.longitude()).stream()
                    .anyMatch(a -> sameName(core, a.core())
                            && meters(place, a.value().latitude(), a.value().longitude()) <= SAME_PLACE_METERS)
                    || samePhoneNearby(place, phones);
            if (!copy) {
                added.add(place);
                addedGrid.add(place.latitude(), place.longitude(), new Named<>(place, core));
                phones.add(place.latitude(), place.longitude(), place);
            }
        }
        return new Result(confirmed, added);
    }

    private static boolean samePhoneNearby(OverturePlace place, Grid<OverturePlace> phones) {
        String phone = phoneKey(place.phone());
        return phone != null && phones.near(place.latitude(), place.longitude()).stream()
                .anyMatch(o -> phone.equals(phoneKey(o.phone()))
                        && meters(place, o.latitude(), o.longitude()) <= SAME_PHONE_METERS);
    }

    // The last 10 digits ("+90 262 742 41 04" = "0262 742 41 04"); null when too short to identify a business
    static String phoneKey(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        return digits.length() < 10 ? null : digits.substring(digits.length() - 10);
    }

    // The folded name without generic words; the folded full name when nothing else is left ("Cafe Nero" -> "nero")
    static String coreName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder core = new StringBuilder();
        for (String word : name.split("[\\s\\-&/,.()]+")) {
            String folded = OsmPlaceMapper.fold(word);
            if (!folded.isEmpty() && !GENERIC_WORDS.contains(folded)) {
                core.append(folded);
            }
        }
        return core.isEmpty() ? OsmPlaceMapper.fold(name) : core.toString();
    }

    /**
     * One distinct word in common ("Zeze Dondurm Çiğ köfte Kafe" / "Zeze Cig Kofte Dondurma Pilav Cafe"), allowing
     * one typo in longer words ("Izgara Izgara" / "Izzgara Izzgara"). Only used within MATCH_METERS.
     */
    static boolean sharesToken(String a, String b) {
        List<String> left = tokens(a);
        List<String> right = tokens(b);
        for (String x : left) {
            for (String y : right) {
                if (x.length() >= MIN_TOKEN_LENGTH && x.equals(y)
                        || Math.min(x.length(), y.length()) >= TYPO_LENGTH && oneEditApart(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Folded distinct words; all folded words when every word is generic ("Izgara Izgara")
    static List<String> tokens(String name) {
        if (name == null) {
            return List.of();
        }
        List<String> all = new ArrayList<>();
        List<String> distinct = new ArrayList<>();
        for (String word : name.split("[\\s\\-&/,.()']+")) {
            String folded = OsmPlaceMapper.fold(word);
            if (!folded.isEmpty()) {
                all.add(folded);
                if (!GENERIC_WORDS.contains(folded)) {
                    distinct.add(folded);
                }
            }
        }
        return distinct.isEmpty() ? all : distinct;
    }

    // Levenshtein distance <= 1
    static boolean oneEditApart(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (Math.abs(a.length() - b.length()) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        boolean edited = false;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (edited) {
                return false;
            }
            edited = true;
            if (a.length() > b.length()) {
                i++;
            } else if (b.length() > a.length()) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return !edited || i == a.length() && j == b.length();
    }

    // The whole name inside the other ("Ali Ustanın Yeri" / "Çorbacı Ali Ustanın Yeri"), long enough to be specific
    static boolean containsFullName(String a, String b) {
        String x = OsmPlaceMapper.fold(a);
        String y = OsmPlaceMapper.fold(b);
        String shorter = x.length() <= y.length() ? x : y;
        String longer = shorter == x ? y : x;
        return shorter.length() >= MIN_FULL_NAME_LENGTH && longer.contains(shorter);
    }

    static boolean sameName(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        return shorter.length() >= MIN_CONTAINED_LENGTH && longer.contains(shorter);
    }

    private static double meters(OverturePlace place, double latitude, double longitude) {
        return meters(place.latitude(), place.longitude(), latitude, longitude);
    }

    // Haversine; plenty accurate for 100 m
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }

    private record Named<T>(T value, String core) {
    }

    // Cells of CELL_LAT x CELL_LON (~170 m in Turkey); near() returns the 3 x 3 cells around a point
    private static final class Grid<T> {

        private final Map<Long, List<T>> cells = new HashMap<>();

        void add(double latitude, double longitude, T value) {
            cells.computeIfAbsent(key(row(latitude), column(longitude)), k -> new ArrayList<>()).add(value);
        }

        List<T> near(double latitude, double longitude) {
            long row = row(latitude);
            long column = column(longitude);
            List<T> found = new ArrayList<>();
            for (long r = row - 1; r <= row + 1; r++) {
                for (long c = column - 1; c <= column + 1; c++) {
                    List<T> cell = cells.get(key(r, c));
                    if (cell != null) {
                        found.addAll(cell);
                    }
                }
            }
            return found;
        }

        private static long row(double latitude) {
            return (long) Math.floor(latitude / CELL_LAT);
        }

        private static long column(double longitude) {
            return (long) Math.floor(longitude / CELL_LON);
        }

        private static long key(long row, long column) {
            return row * 1_000_000L + column;
        }
    }
}

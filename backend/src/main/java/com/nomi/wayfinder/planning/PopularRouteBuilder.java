package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.osm.OsmPlaceMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * "Popüler rotalar" as people actually walk them: the most popular sights of an area that lie close together,
 * in walking order, with lunch / coffee / dessert where the day needs them. Pure logic (no database), unit tested.
 *
 * 1. Seeds: sights (attractions, museums, parks, culture venues) ranked by their real-world popularity
 *    (Wikipedia sitelinks + pageviews, popularity/PlacePopularity); places without it only count when they are
 *    verified, rated or have a photo (a small fallback score), never by guessing.
 * 2. Walkable groups: the most popular unassigned sight is a group's centre, every unassigned sight within
 *    CLUSTER_RADIUS_METERS on the same side of the Bosphorus joins it; repeat. (A DBSCAN-like chain with a 1 km eps
 *    would join the whole historic peninsula with Beyoğlu, so groups are centred.) Groups are ranked by the summed
 *    score of their best MAX_SIGHTS sights; groups with fewer than MIN_SIGHTS are dropped.
 * 3. Per group: its best 3-5 sights that are open that day, visited in the shortest walking order (every order is
 *    tried: at most 5! = 120) in which each sight is open when we get there; meals are inserted as the day
 *    advances (lunch after 12:00, a coffee break ~1.5 h after lunch or at the end, dessert late in the afternoon,
 *    dinner only after 18:30). Sight-to-sight walking is capped at MAX_SIGHT_WALK_MINUTES.
 * 4. The result is a list of PlanningSlots for the real RoutePlanner: pinned sights (it re-checks opening hours,
 *    weather and distance) and meal slots it fills with an open, well-scored place near the previous stop.
 */
public final class PopularRouteBuilder {

    public static final LocalTime DAY_START = LocalTime.of(9, 30);
    public static final LocalTime DAY_END = LocalTime.of(21, 0);
    public static final int PARTY_SIZE = 1;
    public static final WalkingTolerance WALKING = WalkingTolerance.MEDIUM;
    // Place tags the planner prefers for meals / replacements
    public static final List<String> INTERESTS = List.of("history", "museum", "view", "local", "traditional");

    static final double CLUSTER_RADIUS_METERS = 900;
    public static final int MIN_SIGHTS = 3;
    static final int MAX_SIGHTS = 5;
    static final int MAX_SIGHT_WALK_MINUTES = 60;
    // Estimated walk to a meal place near the path (the planner picks the real one)
    static final int MEAL_WALK_MINUTES = 5;
    static final LocalTime LUNCH_FROM = LocalTime.of(12, 0);
    static final LocalTime LUNCH_TARGET = LocalTime.of(12, 30);
    static final LocalTime DESSERT_FROM = LocalTime.of(16, 0);
    static final LocalTime DINNER_FROM = LocalTime.of(18, 30);
    static final LocalTime DINNER_TARGET = LocalTime.of(19, 0);
    static final int COFFEE_AFTER_LUNCH_MINUTES = 90;

    private PopularRouteBuilder() {
    }

    /**
     * A sight that can seed a popular route.
     *
     * @param score real-world interest (popularity), or the small fallback score (see fallbackScore)
     */
    public record Sight(Place place, double score) {

        double latitude() {
            return place.getLatitude();
        }

        double longitude() {
            return place.getLongitude();
        }

        // How long people usually stay: the place's own value, else by kind
        int visitMinutes() {
            if (place.getAvgVisitMinutes() != null) {
                return place.getAvgVisitMinutes();
            }
            if (place.hasTag("religious")) {
                return 30;
            }
            if (place.hasTag("view")) {
                return 25;
            }
            return switch (place.getCategory()) {
                case MUSEUM -> 75;
                case PARK -> 40;
                default -> 45;
            };
        }
    }

    /**
     * Places without Wikipedia data: verified by us (+3), rated (rating - 3, at most +2), with a photo (+1).
     * 0 = no signal at all: such a place is never a "popular" seed.
     */
    public static double fallbackScore(boolean verified, Double rating, boolean hasImage) {
        return (verified ? 3 : 0) + (rating == null ? 0 : Math.max(0, Math.min(2, rating - 3))) + (hasImage ? 1 : 0);
    }

    public static boolean isSightCategory(PlaceCategory category) {
        return StopType.SIGHTSEEING.getCategories().contains(category);
    }

    // ---------- 2. walkable groups ----------

    public record Cluster(Sight centre, List<Sight> sights) {

        // Summed score of the best MAX_SIGHTS sights (sights are sorted best first)
        public double score() {
            return sights.stream().limit(MAX_SIGHTS).mapToDouble(Sight::score).sum();
        }
    }

    /**
     * @param sights candidates, any order; duplicates of one place (same Wikidata item or folded name) are merged
     * @return groups with at least MIN_SIGHTS sights, best first
     */
    public static List<Cluster> clusters(List<Sight> sights) {
        List<Sight> sorted = new ArrayList<>(dedupe(sights));
        sorted.sort(BY_SCORE);
        List<Cluster> clusters = new ArrayList<>();
        Set<Long> assigned = new HashSet<>();
        for (Sight centre : sorted) {
            if (assigned.contains(centre.place().getId())) {
                continue;
            }
            List<Sight> members = new ArrayList<>();
            for (Sight sight : sorted) {
                if (!assigned.contains(sight.place().getId())
                        && meters(centre, sight) <= CLUSTER_RADIUS_METERS
                        && BosphorusSides.sameSide(centre.latitude(), centre.longitude(), sight.latitude(), sight.longitude())) {
                    members.add(sight);
                }
            }
            members.forEach(m -> assigned.add(m.place().getId()));
            if (members.size() >= MIN_SIGHTS) {
                clusters.add(new Cluster(centre, members));
            }
        }
        clusters.sort(Comparator.comparingDouble(Cluster::score).reversed()
                .thenComparing(c -> c.centre().place().getId()));
        return clusters;
    }

    private static final Comparator<Sight> BY_SCORE = Comparator.comparingDouble(Sight::score).reversed()
            .thenComparing(s -> s.place().getId());

    // The same sight mapped twice (a mosque as node and as way, "Sultanahmet Camii" / "Sultan Ahmed Camii")
    static List<Sight> dedupe(List<Sight> sights) {
        List<Sight> sorted = new ArrayList<>(sights);
        sorted.sort(BY_SCORE);
        Set<String> seen = new HashSet<>();
        List<Sight> kept = new ArrayList<>();
        for (Sight sight : sorted) {
            String wikidata = sight.place().getWikidata();
            String name = OsmPlaceMapper.fold(sight.place().getName());
            boolean duplicate = (wikidata != null && seen.contains("q:" + wikidata)) || seen.contains("n:" + name);
            if (!duplicate) {
                kept.add(sight);
            }
            if (wikidata != null) {
                seen.add("q:" + wikidata);
            }
            seen.add("n:" + name);
        }
        return kept;
    }

    // ---------- 3. the day ----------

    /**
     * One stop of the day plan before the planner fills it.
     *
     * @param sight   the pinned sight, null for a meal / coffee slot
     * @param arrival estimated arrival (the planner computes the real one)
     */
    public record Item(StopType type, Sight sight, LocalTime arrival, int minutes) {
    }

    /**
     * @param items         sights and meals in visiting order
     * @param walkMinutes   estimated sight-to-sight walking (meals not included)
     */
    public record Itinerary(List<Item> items, int walkMinutes) {

        public List<Sight> sights() {
            return items.stream().filter(i -> i.sight() != null).map(Item::sight).toList();
        }
    }

    /**
     * The cluster's day: its best open sights in the shortest feasible walking order, with meals.
     *
     * @return empty when fewer than MIN_SIGHTS sights can be visited that day
     */
    public static Optional<Itinerary> itinerary(Cluster cluster, LocalDate date) {
        // Closed the whole day (e.g. museums on Mondays): leave room for the next best sight
        List<Sight> open = cluster.sights().stream().filter(s -> openSomeTime(s, date)).toList();
        List<Sight> chosen = new ArrayList<>(open.subList(0, Math.min(MAX_SIGHTS, open.size())));
        while (chosen.size() >= MIN_SIGHTS) {
            Optional<Itinerary> best = bestOrder(chosen, date);
            if (best.isPresent() && best.get().walkMinutes() <= MAX_SIGHT_WALK_MINUTES) {
                return best;
            }
            // Too far or no order works: drop the least popular sight (the centre always stays)
            chosen.remove(chosen.size() - 1);
        }
        return Optional.empty();
    }

    static boolean openSomeTime(Sight sight, LocalDate date) {
        for (LocalTime t = DAY_START; t.isBefore(LocalTime.of(17, 0)); t = t.plusMinutes(30)) {
            if (!Boolean.FALSE.equals(sight.place().isOpenDuring(date, t, sight.visitMinutes()))) {
                return true;
            }
        }
        return false;
    }

    static Optional<Itinerary> bestOrder(List<Sight> sights, LocalDate date) {
        Itinerary best = null;
        for (List<Sight> order : permutations(sights)) {
            Optional<Itinerary> day = simulate(order, date);
            if (day.isPresent() && (best == null || day.get().walkMinutes() < best.walkMinutes())) {
                best = day.get();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Walks the sights in this order from DAY_START, inserting meals as the clock advances.
     *
     * @return empty when a sight is known to be closed when we would get there
     */
    static Optional<Itinerary> simulate(List<Sight> order, LocalDate date) {
        List<Item> items = new ArrayList<>();
        int clock = minutes(DAY_START);
        int walk = 0;
        boolean lunch = false;
        boolean coffee = false;
        int lunchEnd = 0;
        Sight previous = null;

        for (Sight sight : order) {
            if (previous != null) {
                // A meal before walking on, when it is time
                if (!lunch && clock >= minutes(LUNCH_FROM)) {
                    clock = addMeal(items, StopType.LUNCH, clock, minutes(LUNCH_TARGET) - 30);
                    lunch = true;
                    lunchEnd = clock;
                } else if (lunch && !coffee && clock >= lunchEnd + COFFEE_AFTER_LUNCH_MINUTES) {
                    clock = addMeal(items, StopType.COFFEE, clock, 0);
                    coffee = true;
                }
            }
            int legWalk = previous == null ? 0 : RoutePlanner.walkingMinutes(meters(previous, sight));
            walk += legWalk;
            int arrival = roundUp5(clock + legWalk);
            if (Boolean.FALSE.equals(sight.place().isOpenDuring(date, time(arrival), sight.visitMinutes()))) {
                return Optional.empty();
            }
            items.add(new Item(StopType.SIGHTSEEING, sight, time(arrival), sight.visitMinutes()));
            clock = arrival + sight.visitMinutes();
            previous = sight;
        }

        // End of the walk: lunch if it is (almost) time, a coffee break, dessert / dinner late in the day
        if (!lunch) {
            clock = addMeal(items, StopType.LUNCH, clock, minutes(LUNCH_TARGET) - 30);
        }
        if (!coffee) {
            // Late in the afternoon the break is a dessert (tatlı molası), earlier a coffee
            StopType pause = clock >= minutes(DESSERT_FROM) ? StopType.DESSERT : StopType.COFFEE;
            clock = addMeal(items, pause, clock, 0);
        }
        if (clock >= minutes(DINNER_FROM)) {
            addMeal(items, StopType.DINNER, clock, minutes(DINNER_TARGET) - 30);
        }
        return Optional.of(new Itinerary(List.copyOf(items), walk));
    }

    private static int addMeal(List<Item> items, StopType type, int clock, int notBefore) {
        int arrival = roundUp5(Math.max(clock + MEAL_WALK_MINUTES, notBefore));
        items.add(new Item(type, null, time(arrival), type.getDefaultMinutes()));
        return arrival + type.getDefaultMinutes();
    }

    // ---------- 4. planner input ----------

    /**
     * The planner request of an itinerary: starts at the first sight at DAY_START, one person, no budget.
     * Sights are pinned with their visit length; meals are left to the planner (lunch not before 12:00,
     * dinner not before 18:30, coffee / dessert right after the previous stop).
     */
    public static PlanningRequest request(Itinerary itinerary, LocalDate date) {
        List<PlanningSlot> slots = new ArrayList<>();
        for (Item item : itinerary.items()) {
            slots.add(switch (item.type()) {
                case SIGHTSEEING -> PlanningSlot.popularSight(item.sight().place().getId(), null, item.minutes(), false);
                case LUNCH -> PlanningSlot.at(StopType.LUNCH, LUNCH_TARGET);
                case DINNER -> PlanningSlot.at(StopType.DINNER, DINNER_TARGET);
                default -> PlanningSlot.next(item.type(), null);
            });
        }
        Sight first = itinerary.sights().getFirst();
        return new PlanningRequest(first.latitude(), first.longitude(), date, DAY_START, DAY_END, PARTY_SIZE, null,
                WALKING, INTERESTS, List.copyOf(slots), Set.of(), false);
    }

    /**
     * The request that saves exactly a previewed route: every stop pinned at its planned time and length.
     */
    public static PlanningRequest replay(List<PlannedStopRef> stops, double startLatitude, double startLongitude,
                                         LocalDate date) {
        List<PlanningSlot> slots = stops.stream()
                .map(s -> s.type() == StopType.SIGHTSEEING
                        ? PlanningSlot.popularSight(s.placeId(), s.start(), s.minutes(), true)
                        : new PlanningSlot(s.type(), s.start(), s.placeId(), s.minutes(), true))
                .toList();
        return new PlanningRequest(startLatitude, startLongitude, date, DAY_START, DAY_END, PARTY_SIZE, null,
                WALKING, INTERESTS, slots, Set.of(), false);
    }

    public record PlannedStopRef(StopType type, long placeId, LocalTime start, int minutes) {
    }

    // Stable id of a group's route: its centre sight plus the sights it visits
    public static String key(Cluster cluster, Itinerary itinerary) {
        long[] ids = itinerary.sights().stream().mapToLong(s -> s.place().getId()).sorted().toArray();
        return Long.toString(cluster.centre().place().getId(), 36) + "-" + Integer.toHexString(Arrays.hashCode(ids));
    }

    // ---------- title ----------

    /**
     * A named area (place=quarter / suburb / neighbourhood node).
     */
    public record Area(String name, String kind, double latitude, double longitude) {
    }

    // An area names a route only when it lies within this distance of one of its sights
    static final double AREA_NAME_MAX_METERS = 1000;

    private static boolean nearSomeSight(Area area, List<Sight> sights, double maxMeters) {
        return sights.stream().anyMatch(s ->
                meters(s.latitude(), s.longitude(), area.latitude(), area.longitude()) <= maxMeters);
    }

    /**
     * The route's title from real area names: the area whose name the sights and places around carry ("Galata"
     * for "Galata Kulesi", "Sultanahmet" for "Sultanahmet Camii"), else the well-known quarter (semt) nearest to
     * most sights, else the district. Two areas that both name places ("Galata" + "Karaköy") are joined with "–".
     *
     * @param nearbyNames names of places in and around the group
     * @param used        titles already given to other routes of the same list (not repeated)
     * @return the area name(s), or null when no area is near (the caller then uses the district)
     */
    public static String areaTitle(List<Sight> sights, List<Area> areas, List<String> nearbyNames, Set<String> used) {
        Set<String> sightNames = new HashSet<>();
        sights.forEach(s -> sightNames.add(OsmPlaceMapper.fold(s.place().getName())));
        List<String> others = nearbyNames.stream().map(OsmPlaceMapper::fold).toList();

        Map<String, Double> votes = new LinkedHashMap<>();
        Map<String, Integer> nameMatches = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        for (Area area : areas) {
            String folded = OsmPlaceMapper.fold(area.name());
            // Only areas the group is actually in: "Galata" restaurants near Eminönü do not make Fatih "Galata"
            if (!usableAreaName(folded) || !nearSomeSight(area, sights, AREA_NAME_MAX_METERS)) {
                continue;
            }
            display.putIfAbsent(folded, area.name());
            int matches = 3 * (int) sightNames.stream().filter(n -> n.contains(folded)).count()
                    + (int) others.stream().filter(n -> n.contains(folded)).count();
            nameMatches.merge(folded, matches, Math::max);
            if (matches > 0) {
                // A name carried by the sights themselves outweighs being merely close
                votes.merge(folded, 2.0 * matches, Math::max);
            }
        }
        // Proximity: each sight votes for its nearest semt (quarter) within 700 m, else nearest area within 400 m
        for (Sight sight : sights) {
            nearest(sight, areas, "quarter", 700).or(() -> nearest(sight, areas, null, 400)).ifPresent(area -> {
                String folded = OsmPlaceMapper.fold(area.name());
                if (usableAreaName(folded)) {
                    display.putIfAbsent(folded, area.name());
                    votes.merge(folded, 1.0, Double::sum);
                }
            });
        }

        List<Map.Entry<String, Double>> ranked = votes.entrySet().stream()
                .filter(e -> !used.contains(display.get(e.getKey())))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .toList();
        if (ranked.isEmpty()) {
            return null;
        }
        String first = ranked.get(0).getKey();
        if (ranked.size() > 1) {
            Map.Entry<String, Double> second = ranked.get(1);
            if (nameMatches.getOrDefault(first, 0) > 0 && nameMatches.getOrDefault(second.getKey(), 0) > 0
                    && second.getValue() >= ranked.get(0).getValue() / 2
                    && !first.contains(second.getKey()) && !second.getKey().contains(first)) {
                return display.get(first) + "–" + display.get(second.getKey());
            }
        }
        return display.get(first);
    }

    // Area names that are also common words in place names ("Merkez", "Çarşı", "Cumhuriyet") say nothing
    private static final Set<String> VAGUE_AREA_NAMES = Set.of("merkez", "carsi", "cumhuriyet", "ataturk", "yeni",
            "eski", "sahil", "istasyon", "cami", "camii", "hurriyet", "sultan", "park", "liman", "pazar", "bahce");

    static boolean usableAreaName(String folded) {
        return folded.length() >= 4 && !VAGUE_AREA_NAMES.contains(folded);
    }

    private static Optional<Area> nearest(Sight sight, List<Area> areas, String kind, double maxMeters) {
        return areas.stream()
                .filter(a -> kind == null || kind.equals(a.kind()))
                .filter(a -> meters(sight.latitude(), sight.longitude(), a.latitude(), a.longitude()) <= maxMeters)
                .min(Comparator.comparingDouble(a -> meters(sight.latitude(), sight.longitude(), a.latitude(), a.longitude())));
    }

    // ---------- helpers ----------

    static <T> List<List<T>> permutations(List<T> items) {
        List<List<T>> result = new ArrayList<>();
        permute(new ArrayList<>(items), 0, result);
        return result;
    }

    private static <T> void permute(List<T> items, int k, List<List<T>> result) {
        if (k == items.size()) {
            result.add(List.copyOf(items));
            return;
        }
        for (int i = k; i < items.size(); i++) {
            Collections.swap(items, k, i);
            permute(items, k + 1, result);
            Collections.swap(items, k, i);
        }
    }

    static double meters(Sight a, Sight b) {
        return meters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }

    public static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
    }

    private static int minutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static LocalTime time(int dayMinutes) {
        int m = Math.floorMod(dayMinutes, 1440);
        return LocalTime.of(m / 60, m % 60);
    }

    private static int roundUp5(int minutes) {
        return (int) (Math.ceil(minutes / 5.0) * 5);
    }
}

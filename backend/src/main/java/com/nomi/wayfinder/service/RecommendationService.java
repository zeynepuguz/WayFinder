package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.NearbyPlaceResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.UserPreferences;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.planning.PlaceSuitability;
import com.nomi.wayfinder.planning.BosphorusSides;
import com.nomi.wayfinder.planning.PlaceScorer;
import com.nomi.wayfinder.planning.RoutePlanner;
import com.nomi.wayfinder.planning.WeatherContext;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.weather.WeatherForecast;
import com.nomi.wayfinder.weather.WeatherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

// "What should I do right now near me?" - same scoring as the route planner, for a single stop
@Service
public class RecommendationService {

    // "Better but farther": how far to look, and how much better (fit score, distance ignored) it must be
    static final double FARTHER_MAX_RADIUS = 5000;
    static final double FARTHER_MIN_MARGIN = 8;
    // When nothing nearby is open / suitable, farther places still need to be a decent fit
    static final double MIN_FIT_WITHOUT_NEARBY = 70;
    static final int FARTHER_CANDIDATE_LIMIT = 150;
    // Home suggestions: how far (minutes on foot), how many places to look at and how many good ones to pick from
    static final int HOME_WALK_MINUTES = 30;
    static final int HOME_CANDIDATE_LIMIT = 250;
    static final int HOME_POOL = 12;

    private final PlaceRepository placeRepository;
    private final PlaceService placeService;
    private final PlaceScorer scorer;
    private final PlaceMapper placeMapper;
    private final WeatherService weatherService;
    private final UserService userService;
    private final Clock clock;

    public RecommendationService(
            PlaceRepository placeRepository,
            PlaceService placeService,
            PlaceScorer scorer,
            PlaceMapper placeMapper,
            WeatherService weatherService,
            UserService userService,
            Clock clock
    ) {
        this.placeRepository = placeRepository;
        this.placeService = placeService;
        this.scorer = scorer;
        this.placeMapper = placeMapper;
        this.weatherService = weatherService;
        this.userService = userService;
        this.clock = clock;
    }

    @Transactional
    public List<Recommendation> recommend(double latitude, double longitude, StopType type, Long userId, int limit) {
        Context ctx = context(latitude, longitude, userId);
        return toNearby(evaluate(ctx, type, nearbyCandidates(ctx, type)), type, limit);
    }

    /**
     * Home screen "Şimdi için": anywhere within a HOME_WALK_MINUTES walk, a different few of the best HOME_POOL places on
     * each visit. Candidates are a random sample of the whole circle: by distance, the same nearest few would always win.
     */
    @Transactional
    public List<Recommendation> recommendAround(double latitude, double longitude, StopType type, Long userId,
                                                int limit) {
        Context near = context(latitude, longitude, userId);
        double radius = RoutePlanner.metersWithin(HOME_WALK_MINUTES);
        Context ctx = new Context(latitude, longitude, near.today(), near.now(), near.interests(), near.forecast(),
                radius);
        List<PlaceDistance> found = placeRepository.sampleCandidates(latitude, longitude, radius, categories(type),
                type.getMatchingTag(), HOME_CANDIDATE_LIMIT);
        // The best fits (open, weather, interests, rating), distance left out: anywhere in the circle is fine
        List<Evaluated> best = evaluate(ctx, type, found).stream()
                .sorted(Comparator.comparingDouble((Evaluated e) -> e.scored().fitScore()).reversed())
                .limit(HOME_POOL)
                .toList();
        List<Recommendation> pool = new ArrayList<>(toNearby(best, type, HOME_POOL));
        Collections.shuffle(pool);
        return pool.stream().limit(limit)
                .sorted(Comparator.comparingDouble(r -> r.place().getDistanceMeters()))
                .toList();
    }

    /**
     * Nearby suggestions (same as recommend) plus up to fartherLimit places between the nearby radius and
     * FARTHER_MAX_RADIUS that suit the request clearly better (fit score, i.e. ignoring distance, at least
     * FARTHER_MIN_MARGIN above the best nearby one). When nothing nearby fits, any good farther place is shown.
     */
    @Transactional
    public TieredRecommendations recommendTiered(double latitude, double longitude, StopType type, Long userId,
                                                 int nearbyLimit, int fartherLimit) {
        Context ctx = context(latitude, longitude, userId);
        List<Evaluated> nearby = evaluate(ctx, type, nearbyCandidates(ctx, type));

        List<PlaceDistance> ring = placeRepository.findCandidatesInRing(
                ctx.latitude(), ctx.longitude(), ctx.radius(), FARTHER_MAX_RADIUS,
                categories(type), type.getMatchingTag(), String.join(",", ctx.interests()), FARTHER_CANDIDATE_LIMIT);

        Evaluated bestNearby = nearby.stream()
                .max(Comparator.comparingDouble(e -> e.scored().fitScore()))
                .orElse(null);

        List<Recommendation> farther = evaluate(ctx, type, ring).stream()
                .filter(e -> bestNearby == null
                        ? e.scored().fitScore() >= MIN_FIT_WITHOUT_NEARBY
                        : e.scored().fitScore() >= bestNearby.scored().fitScore() + FARTHER_MIN_MARGIN)
                .sorted(Comparator.comparingDouble((Evaluated e) -> e.scored().fitScore()).reversed())
                .limit(fartherLimit)
                .map(e -> toFarther(e, bestNearby, ctx, type))
                .toList();

        return new TieredRecommendations(toNearby(nearby, type, nearbyLimit), farther);
    }

    private Context context(double latitude, double longitude, Long userId) {
        WalkingTolerance tolerance = WalkingTolerance.MEDIUM;
        List<String> interests = List.of();
        if (userId != null) {
            UserPreferences preferences = userService.getPreferences(userId);
            tolerance = preferences.getWalkingTolerance();
            interests = preferences.getInterests();
        }

        LocalDate today = LocalDate.now(clock);
        WeatherForecast forecast = weatherService.getForecast(latitude, longitude, today).orElse(null);
        return new Context(latitude, longitude, today, LocalTime.now(clock), interests, forecast,
                tolerance.getMaxLegMeters() * 1.5);
    }

    private List<PlaceDistance> nearbyCandidates(Context ctx, StopType type) {
        return placeRepository.findCandidates(ctx.latitude(), ctx.longitude(), ctx.radius(),
                categories(type), type.getMatchingTag(), 40);
    }

    private static List<String> categories(StopType type) {
        return type.getCategories().stream().map(PlaceCategory::name).toList();
    }

    // Scores the candidates that are not closed on arrival (same rules for both tiers)
    private List<Evaluated> evaluate(Context ctx, StopType type, List<PlaceDistance> found) {
        Map<Long, Place> places = placeService.loadPlaces(found);
        List<Evaluated> results = new ArrayList<>();

        for (PlaceDistance candidate : found) {
            Place place = places.get(candidate.getId());
            if (place == null
                    // A take-away bakery or a kıraathane is not a suggestion (still listed in Explore)
                    || !PlaceSuitability.isStop(place)
                    // No walking across the Bosphorus
                    || !BosphorusSides.sameSide(ctx.latitude(), ctx.longitude(), place.getLatitude(), place.getLongitude())) {
                continue;
            }
            int walking = RoutePlanner.walkingMinutes(candidate.getDistanceMeters());
            LocalTime arrival = ctx.now().plusMinutes(walking);
            int duration = place.getAvgVisitMinutes() != null ? place.getAvgVisitMinutes() : type.getDefaultMinutes();

            Boolean open = place.isOpenDuring(ctx.today(), arrival, duration);
            if (Boolean.FALSE.equals(open)) {
                continue;
            }

            WeatherContext weather = WeatherContext.at(ctx.forecast(), arrival, false);
            // Price: null = unknown (not free); there is no budget here, so it only changes the reason text
            PlaceScorer.ScoredPlace scored = scorer.score(new PlaceScorer.Candidate(
                    place, candidate.getDistanceMeters(), ctx.radius(), walking, true, arrival,
                    weather, ctx.interests(), place.getEstimatedCost(), null, open));

            results.add(new Evaluated(place, candidate.getDistanceMeters(), walking, arrival, open, weather, scored));
        }
        return results;
    }

    private List<Recommendation> toNearby(List<Evaluated> evaluated, StopType type, int limit) {
        return evaluated.stream()
                .map(e -> new Recommendation(
                        placeMapper.toNearbyResponse(e.place(), e.distanceMeters()),
                        type,
                        Math.round(e.scored().score() * 10) / 10.0,
                        e.scored().reasons(),
                        null))
                .sorted(Comparator.comparingDouble(Recommendation::score).reversed())
                .limit(limit)
                .toList();
    }

    private Recommendation toFarther(Evaluated e, Evaluated bestNearby, Context ctx, StopType type) {
        // The scorer's first reason is always its "X m (~N dk)" line; a farther place leads with km instead
        List<String> reasons = new ArrayList<>();
        reasons.add(distanceLine(e.distanceMeters(), e.walkingMinutes()));
        reasons.addAll(e.scored().reasons().subList(1, e.scored().reasons().size()));

        return new Recommendation(
                placeMapper.toNearbyResponse(e.place(), e.distanceMeters()),
                type,
                Math.round(e.scored().score() * 10) / 10.0,
                reasons,
                whyBetter(e, bestNearby, ctx.interests()));
    }

    // "1,8 km uzakta (yürüyerek ~24 dk)" / "1.8 km away (~24 min walk)"; walking time only, no transit guesses
    static String distanceLine(double distanceMeters, int walkingMinutes) {
        double km = distanceMeters / 1000;
        return Texts.english()
                ? String.format(Locale.ROOT, "%.1f km away (~%d min walk)", km, walkingMinutes)
                : String.format(Texts.TURKISH, "%.1f km uzakta (yürüyerek ~%d dk)", km, walkingMinutes);
    }

    /**
     * One short sentence from the factors that really make the farther place a better fit than the
     * best nearby one. Only compares facts we have (real rating, indoor vs weather, interest tags,
     * evening sea/view, known opening hours); never invents a reason. null when none applies.
     */
    static String whyBetter(Evaluated far, Evaluated nearby, List<String> interests) {
        if (nearby == null) {
            return Texts.t("Yakında açık ve uygun bir seçenek yok", "Nothing suitable is open nearby");
        }

        List<String> parts = new ArrayList<>();
        Double farRating = far.place().getRating();
        Double nearRating = nearby.place().getRating();
        if (farRating != null && (nearRating == null || farRating >= nearRating + 0.1)) {
            // Nearby places without a rating (OpenStreetMap) cannot be called "lower rated"
            parts.add(String.format(Locale.ROOT, nearRating == null
                    ? Texts.t("puanı yüksek (%.1f), yakındakilerin puan bilgisi yok", "well rated (%.1f); no ratings for the nearby ones")
                    : Texts.t("puanı daha yüksek (%.1f)", "higher rating (%.1f)"), farRating));
        }
        if (far.weather().badForOutdoor() && far.place().isIndoor() && !nearby.place().isIndoor()) {
            parts.add(Texts.t("hava " + far.weather().reasonLabel() + " ve burası kapalı mekan",
                    "indoors while the weather is " + far.weather().reasonLabel()));
        }
        if (interestMatches(far.place(), interests) > interestMatches(nearby.place(), interests)) {
            parts.add(Texts.t("ilgi alanına uygun", "matches your interests"));
        }
        if (eveningView(far) && !eveningView(nearby)) {
            parts.add(Texts.t("akşam deniz/manzara keyfi", "sea and views in the evening"));
        }
        if (Boolean.TRUE.equals(far.open()) && nearby.open() == null) {
            parts.add(Texts.t("açık olduğu biliniyor", "known to be open"));
        }

        if (parts.isEmpty()) {
            return null;
        }
        String joined = parts.size() == 1 ? parts.getFirst()
                : String.join(", ", parts.subList(0, parts.size() - 1))
                + Texts.t(" ve ", " and ") + parts.getLast();
        return joined.substring(0, 1).toUpperCase(Texts.locale()) + joined.substring(1);
    }

    private static long interestMatches(Place place, List<String> interests) {
        return interests.stream().filter(place::hasTag).count();
    }

    // Same condition as the scorer's evening bonus
    private static boolean eveningView(Evaluated e) {
        return !e.place().isIndoor() && !e.weather().badForOutdoor()
                && !e.arrival().isBefore(LocalTime.of(17, 30))
                && (e.place().hasTag("sea") || e.place().hasTag("view"));
    }

    // Which kind of stop makes sense at this time of day
    public static StopType suggestedTypeAt(LocalTime time) {
        if (time.isBefore(LocalTime.of(10, 30))) {
            return StopType.BREAKFAST;
        }
        if (time.isBefore(LocalTime.of(12, 0))) {
            return StopType.SIGHTSEEING;
        }
        if (time.isBefore(LocalTime.of(14, 30))) {
            return StopType.LUNCH;
        }
        if (time.isBefore(LocalTime.of(17, 0))) {
            return StopType.COFFEE;
        }
        if (time.isBefore(LocalTime.of(19, 0))) {
            return StopType.DESSERT;
        }
        if (time.isBefore(LocalTime.of(22, 30))) {
            return StopType.DINNER;
        }
        return StopType.DESSERT;
    }

    /**
     * @param whyBetter only for "better but farther" items: what makes it a better fit than the nearby ones;
     *                  null for nearby items
     */
    public record Recommendation(NearbyPlaceResponse place, StopType type, double score, List<String> reasons,
                                 String whyBetter) {
    }

    public record TieredRecommendations(List<Recommendation> nearby, List<Recommendation> farther) {
    }

    private record Context(double latitude, double longitude, LocalDate today, LocalTime now,
                           List<String> interests, WeatherForecast forecast, double radius) {
    }

    record Evaluated(Place place, double distanceMeters, int walkingMinutes, LocalTime arrival, Boolean open,
                     WeatherContext weather, PlaceScorer.ScoredPlace scored) {
    }
}

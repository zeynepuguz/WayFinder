package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.osm.OsmPlaceMapper;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.service.Interests;
import com.nomi.wayfinder.weather.WeatherForecast;
import com.nomi.wayfinder.weather.WeatherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a day plan. For each slot it asks PostGIS for nearby candidates, removes places that are closed / already
 * used / far over budget / inside an institution, scores the rest and picks the best - then shapes the route:
 *
 * 1. Look-ahead: a first greedy pass shows where the day will roughly go; a second pass penalizes candidates far
 *    from the centroid of the stops still to come, so consecutive stops progress along one path instead of
 *    jumping back and forth. The pass with less walking wins (same number of stops, no fewer interest matches).
 * 2. Order: the flexible stops between two meals (sightseeing, coffee, dessert) are put in the order with the least
 *    walking in which every stop is still open when we get there and the meals keep their times.
 * 3. Interests really shape the plan: a strong score bonus (InterestMatcher), and a sightseeing stop is chosen among
 *    the matching places whenever one exists within the walking radius (a little further if needed, with a note).
 *    The notes say which interests were used and which could not be matched in the area.
 * 4. Walking tolerance caps each leg (LOW: 600 m, a search further out only when nothing is near, with a note).
 * 5. Breakfast: OSM has few "breakfast" places, so when none is open nearby an open café / bakery / pastry shop is
 *    taken instead (said in a note) rather than leaving the first stop empty.
 *
 * Deterministic on purpose: the LLM never decides places, prices or distances; it only turns user text into a
 * request and explains the result.
 */
@Service
public class RoutePlanner {

    // Straight-line meters are shorter than real streets
    static final double DETOUR_FACTOR = 1.3;
    static final double WALKING_METERS_PER_MINUTE = 75;
    // When the first search finds nothing, look this much further once ("az yürüyelim": only a little further)
    static final double EXPANDED_SEARCH_FACTOR = 1.8;
    static final double EXPANDED_SEARCH_FACTOR_LOW = 1.5;
    static final int CANDIDATE_LIMIT = 40;
    static final int INTEREST_CANDIDATE_LIMIT = 30;
    // How early before its target time a stop may start (a meal should not start hours early)
    static final int MEAL_FLEX_MINUTES = 30;
    static final int OTHER_FLEX_MINUTES = 90;
    // A sight / coffee / dessert has no fixed time: never wait longer than this for its target time
    // (otherwise a park after lunch waits until 15:00 and the day has a 1.5 h hole)
    static final int FLEXIBLE_MAX_WAIT_MINUTES = 20;
    // A reordered meal may start at most this much after its target time
    static final int MEAL_LATE_MINUTES = 60;
    // Look-ahead: score points per maxLeg of distance from the centroid of the stops still to come
    static final double LOOKAHEAD_WEIGHT = 15;
    // First pass: a mild pull towards the start point
    static final double START_PULL_WEIGHT = 6;
    // A new order must save at least this much walking to be worth changing the plan
    static final double MIN_REORDER_SAVING_METERS = 60;
    // Two entries of one place (a café and "... Cafe ve Restaurant" next door) are not two stops
    static final double SAME_PLACE_METERS = 80;
    // "Az yürüyelim": say so when the day still needs more walking than this
    static final int LOW_TOTAL_WALKING_MINUTES = 25;

    // Interests that are only met outdoors
    static final Set<String> OUTDOOR_INTERESTS = Set.of("sea", "nature", "view");

    private static final Set<StopType> FLEXIBLE = EnumSet.of(StopType.SIGHTSEEING, StopType.COFFEE, StopType.DESSERT);

    private final PlaceRepository placeRepository;
    private final PlaceScorer scorer;
    private final WeatherService weatherService;

    public RoutePlanner(PlaceRepository placeRepository, PlaceScorer scorer, WeatherService weatherService) {
        this.placeRepository = placeRepository;
        this.scorer = scorer;
        this.weatherService = weatherService;
    }

    @Transactional(readOnly = true)
    public PlanResult plan(PlanningRequest request) {
        Optional<WeatherForecast> forecast = weatherService.getForecast(
                request.startLatitude(), request.startLongitude(), request.date());

        List<String> notes = new ArrayList<>();
        if (forecast.isEmpty() && !request.assumeWet()) {
            notes.add(Texts.t("Hava durumu bilgisi alınamadı; plan hava durumu dikkate alınmadan oluşturuldu.",
                    "Weather information was not available; the plan was made without taking the weather into account."));
        }

        String advice = forecast
                .map(f -> weatherService.advice(f, request.startTime(), request.endTime()))
                .orElse(request.assumeWet()
                        ? Texts.t("Yağmur nedeniyle kapalı mekanlara öncelik verdim.",
                        "I prioritized indoor places because of the rain.")
                        : null);

        // Replans and popular routes pin their stops: keep their order and places as they are
        boolean shape = request.slots().stream().noneMatch(s -> s.pinnedPlaceId() != null || s.exactTime());

        Pass pass = greedy(request, forecast.orElse(null), shape ? startPull(request) : Map.of(),
                shape ? START_PULL_WEIGHT : 0);
        if (shape && pass.chosen().size() >= 3) {
            Pass lookAhead = greedy(request, forecast.orElse(null), anchors(pass), LOOKAHEAD_WEIGHT);
            if (better(lookAhead, pass, request.interests())) {
                pass = lookAhead;
            }
        }
        List<Chosen> chosen = pass.chosen();
        if (shape) {
            chosen = reorder(chosen, request, forecast.orElse(null));
        }
        List<PlannedStop> stops = chosen.stream().map(Chosen::stop).toList();
        notes.addAll(pass.notes());

        Integer remainingBudget = request.budget();
        if (remainingBudget != null) {
            remainingBudget -= stops.stream().mapToInt(s -> s.totalCost(request.partySize())).sum();
            if (remainingBudget < 0) {
                notes.add(Texts.t("Tahmini harcama bütçeyi yaklaşık " + (-remainingBudget) + " TL aşıyor.",
                        "Estimated spending is about " + (-remainingBudget) + " TL over the budget."));
            }
        }

        // Unknown prices are left out of the totals (not counted as free); say so
        long unknownPrice = stops.stream().filter(s -> s.place().getEstimatedCost() == null).count();
        if (unknownPrice > 0) {
            notes.add(unknownPriceNote(unknownPrice));
        }

        if (shape) {
            // Rain at a sightseeing stop: outdoor interests (sea, nature, view) were left out on purpose, not missing
            boolean rainySights = stops.stream().anyMatch(s -> s.type() == StopType.SIGHTSEEING
                    && WeatherContext.at(forecast.orElse(null), s.start(), request.assumeWet()).wet());
            notes.addAll(interestNotes(stops, request.interests(), rainySights));
            int walking = stops.stream().mapToInt(PlannedStop::walkingMinutes).sum();
            if (request.walkingTolerance() == WalkingTolerance.LOW && walking > LOW_TOTAL_WALKING_MINUTES) {
                notes.add(Texts.t("Az yürüyüş istedin; bu bölgede uygun mekanlar dağınık olduğu için toplam yürüyüş ~"
                                + walking + " dk.",
                        "You asked for little walking; suitable places are spread out here, so the walks add up to ~"
                                + walking + " min."));
            }
        }

        return new PlanResult(stops, notes, forecast, advice);
    }

    // ================= GREEDY PASS =================

    /**
     * One stop after the other, in slot order.
     *
     * @param anchors slot index -> point the stop should stay close to (look-ahead), with this weight
     */
    private Pass greedy(PlanningRequest request, WeatherForecast forecast, Map<Integer, double[]> anchors,
                        double anchorWeight) {
        List<Chosen> chosen = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<Long> used = new HashSet<>(request.excludedPlaceIds());
        // Famous outdoor sights a popular route keeps in the rain on purpose: one umbrella note for all of them
        List<String> rainyKept = new ArrayList<>();

        double latitude = request.startLatitude();
        double longitude = request.startLongitude();
        // Minutes from the start day's midnight; can go past 24:00 for late plans
        int clock = minutes(request.startTime());
        int endOfDay = endMinutes(request.startTime(), request.endTime());

        Integer remainingBudget = request.budget();
        double remainingWeight = request.slots().stream().mapToDouble(s -> s.type().getBudgetWeight()).sum();

        for (int index = 0; index < request.slots().size(); index++) {
            PlanningSlot slot = request.slots().get(index);
            Double allowance = null;
            if (remainingBudget != null && remainingWeight > 0) {
                allowance = remainingBudget * (slot.type().getBudgetWeight() / remainingWeight);
            }
            remainingWeight -= slot.type().getBudgetWeight();

            double[] anchor = anchors.get(index);
            LegContext leg = new LegContext(latitude, longitude, clock, chosen.isEmpty(), allowance, anchor,
                    anchor == null ? 0 : anchorWeight, chosen.stream().map(c -> c.stop().place()).toList());
            // Notes about this stop only count if the stop ends up in the plan
            List<String> slotNotes = new ArrayList<>();
            Optional<PlannedStop> planned = planSlot(slot, leg, request, forecast, used, slotNotes);

            if (planned.isEmpty()) {
                notes.addAll(slotNotes);
                continue;
            }

            PlannedStop stop = planned.get();
            int stopEnd = toDayMinutes(stop.end(), clock);
            if (stopEnd > endOfDay) {
                notes.add(Texts.t(slot.type().getLabel() + " gün sonuna sığmadığı için eklenmedi.",
                        slot.type().getLabel() + " was not added because it did not fit before the end of the day."));
                continue;
            }

            // The scorer only penalizes outdoor places in rain; say so when nothing indoor was left
            if (!stop.place().isIndoor()
                    && WeatherContext.at(forecast, stop.start(), request.assumeWet()).wet() && slot.keepInRain()) {
                rainyKept.add(stop.place().getDisplayName());
            } else if (!stop.place().isIndoor()
                    && WeatherContext.at(forecast, stop.start(), request.assumeWet()).wet()) {
                slotNotes.add(Texts.t(
                        stop.place().getDisplayName() + " açık alan ve o saatte yağış bekleniyor; yakında uygun kapalı bir "
                                + slot.type().getLabel().toLowerCase(Texts.TURKISH) + " mekanı bulamadım.",
                        stop.place().getDisplayName() + " is outdoors and rain is expected at that time; I could not find a suitable indoor "
                                + Texts.lower(slot.type().getLabel()) + " place nearby."));
            }

            notes.addAll(slotNotes);
            chosen.add(new Chosen(index, slot, stop));
            used.add(stop.place().getId());
            latitude = stop.place().getLatitude();
            longitude = stop.place().getLongitude();
            clock = stopEnd;

            if (remainingBudget != null) {
                remainingBudget -= stop.totalCost(request.partySize());
            }
        }
        if (!rainyKept.isEmpty()) {
            String names = String.join(", ", rainyKept);
            notes.add(Texts.t("Yağış bekleniyor; " + names + " açık alanda ama rotanın asıl görülecek yerleri. "
                            + "Şemsiye almayı unutma.",
                    "Rain is expected; " + names + " " + (rainyKept.size() == 1 ? "is" : "are")
                            + " outdoors but the point of this route. Take an umbrella."));
        }
        return new Pass(chosen, notes);
    }

    // First pass: every stop is pulled a little towards the start (the user's position / the chosen area)
    private static Map<Integer, double[]> startPull(PlanningRequest request) {
        Map<Integer, double[]> anchors = new HashMap<>();
        for (int i = 0; i < request.slots().size(); i++) {
            anchors.put(i, new double[]{request.startLatitude(), request.startLongitude()});
        }
        return anchors;
    }

    // Second pass: slot i is pulled towards the centroid of the first pass' stops after it
    static Map<Integer, double[]> anchors(Pass first) {
        Map<Integer, double[]> anchors = new HashMap<>();
        List<Chosen> chosen = first.chosen();
        for (Chosen c : chosen) {
            List<Chosen> rest = chosen.stream().filter(o -> o.slotIndex() > c.slotIndex()).toList();
            if (rest.isEmpty()) {
                continue;
            }
            double lat = rest.stream().mapToDouble(o -> o.stop().place().getLatitude()).average().orElse(0);
            double lon = rest.stream().mapToDouble(o -> o.stop().place().getLongitude()).average().orElse(0);
            anchors.put(c.slotIndex(), new double[]{lat, lon});
        }
        return anchors;
    }

    // More stops first, then no fewer interest matches, then less walking
    private static boolean better(Pass candidate, Pass current, List<String> interests) {
        if (candidate.chosen().size() != current.chosen().size()) {
            return candidate.chosen().size() > current.chosen().size();
        }
        long candidateMatches = interestMatchCount(candidate, interests);
        long currentMatches = interestMatchCount(current, interests);
        if (candidateMatches != currentMatches) {
            return candidateMatches > currentMatches;
        }
        return walkingMeters(candidate.chosen()) + MIN_REORDER_SAVING_METERS < walkingMeters(current.chosen());
    }

    private static long interestMatchCount(Pass pass, List<String> interests) {
        return pass.chosen().stream()
                .filter(c -> !InterestMatcher.matching(c.stop().place(), interests).isEmpty()).count();
    }

    private static int walkingMeters(List<Chosen> chosen) {
        return chosen.stream().mapToInt(c -> c.stop().distanceFromPreviousMeters()).sum();
    }

    private Optional<PlannedStop> planSlot(
            PlanningSlot slot,
            LegContext leg,
            PlanningRequest request,
            WeatherForecast forecast,
            Set<Long> used,
            List<String> notes
    ) {
        if (slot.pinnedPlaceId() != null && !used.contains(slot.pinnedPlaceId())) {
            Optional<PlannedStop> pinned = planPinned(slot, leg, request, forecast, notes);
            if (pinned.isPresent()) {
                return pinned;
            }
        }

        WeatherContext weatherAtStart = WeatherContext.at(forecast, time(leg.clock()), request.assumeWet());
        double maxLeg = request.walkingTolerance().getMaxLegMeters()
                * (weatherAtStart.wet() || weatherAtStart.hot() ? 0.7 : 1.0);
        double expanded = maxLeg * (request.walkingTolerance() == WalkingTolerance.LOW
                ? EXPANDED_SEARCH_FACTOR_LOW : EXPANDED_SEARCH_FACTOR);
        boolean wantsInterestMatch = slot.type() == StopType.SIGHTSEEING
                && request.interests().stream().anyMatch(InterestMatcher::known);

        // 1) nearby and within budget
        SearchOutcome near = search(slot, leg, request, forecast, used, maxLeg, maxLeg);
        if (near.affordable().isPresent() && (!wantsInterestMatch || near.interestMatched())) {
            return near.affordable();
        }

        // Breakfast: few places are tagged as breakfast places; an open café / bakery / pastry shop nearby will do
        PlanningSlot breakfastFallback = slot.type() == StopType.BREAKFAST && slot.categories() == null
                ? new PlanningSlot(slot.type(), slot.targetTime(), null, slot.durationMinutes(), slot.exactTime(),
                Set.of(PlaceCategory.CAFE, PlaceCategory.DESSERT), "bakery", false)
                : null;
        if (breakfastFallback != null) {
            SearchOutcome cafes = search(breakfastFallback, leg, request, forecast, used, maxLeg, maxLeg);
            if (cafes.affordable().isPresent()) {
                notes.add(breakfastNote(cafes.affordable().get().place()));
                return cafes.affordable();
            }
        }

        // 2) a bit further but within budget
        SearchOutcome far = search(slot, leg, request, forecast, used, expanded, maxLeg);
        if (wantsInterestMatch && far.affordable().isPresent() && far.interestMatched()) {
            Place place = far.affordable().get().place();
            String labels = labels(InterestMatcher.matching(place, request.interests()));
            notes.add(Texts.t(capitalize(labels) + " tercihin için biraz daha uzaktaki " + place.getDisplayName()
                            + " seçildi.",
                    place.getDisplayName() + ", a little further away, was chosen for your " + labels + " interest."));
            return far.affordable();
        }
        if (near.affordable().isPresent()) {
            return near.affordable();
        }
        if (far.affordable().isPresent()) {
            notes.add(Texts.t(slot.type().getLabel() + " için yakında uygun yer bulunamadı; biraz daha uzaktaki "
                            + far.affordable().get().place().getDisplayName() + " seçildi.",
                    "No suitable " + Texts.lower(slot.type().getLabel()) + " place was found nearby; "
                            + far.affordable().get().place().getDisplayName() + ", a little further away, was chosen."));
            return far.affordable();
        }
        if (breakfastFallback != null) {
            SearchOutcome farCafes = search(breakfastFallback, leg, request, forecast, used, expanded, maxLeg);
            if (farCafes.affordable().isPresent()) {
                notes.add(breakfastNote(farCafes.affordable().get().place()));
                return farCafes.affordable();
            }
        }

        // 3) over budget: best overall score (the score already penalizes cost)
        Optional<PlannedStop> fallback = near.overBudget().isPresent() ? near.overBudget() : far.overBudget();
        if (fallback.isEmpty()) {
            notes.add(Texts.t(slot.type().getLabel() + " için bu saatte açık ve uygun bir mekan bulunamadı.",
                    "No suitable " + Texts.lower(slot.type().getLabel()) + " place is open at this time."));
        }
        return fallback;
    }

    private static String breakfastNote(Place place) {
        return Texts.t("Yakında bu saatte açık bir kahvaltı mekanı bulunamadı; kahvaltı için açık olan "
                        + place.getDisplayName() + " (kafe / fırın / pastane) seçildi.",
                "No breakfast place is open nearby at this time; " + place.getDisplayName()
                        + " (a café / bakery / pastry shop that is open) was chosen for breakfast.");
    }

    private SearchOutcome search(
            PlanningSlot slot,
            LegContext leg,
            PlanningRequest request,
            WeatherForecast forecast,
            Set<Long> used,
            double searchRadius,
            double maxLegForScoring
    ) {
        Set<PlaceCategory> categorySet = EnumSet.copyOf(slot.searchCategories());
        // "Uygun fiyat": cafés are the cheaper lunch / dinner when prices are unknown
        if (slot.categories() == null && request.interests().contains("budget")
                && (slot.type() == StopType.LUNCH || slot.type() == StopType.DINNER)) {
            categorySet.add(PlaceCategory.CAFE);
        }
        List<String> categories = categorySet.stream().map(PlaceCategory::name).sorted().toList();

        List<PlaceDistance> found = new ArrayList<>(placeRepository.findCandidates(
                leg.latitude(), leg.longitude(), searchRadius, categories, slot.searchTag(), CANDIDATE_LIMIT));
        // Interest-matching places a little further than the nearest CANDIDATE_LIMIT
        String interestTags = InterestMatcher.tagsCsv(request.interests());
        boolean sea = InterestMatcher.wantsSea(request.interests());
        if (!interestTags.isEmpty() || sea) {
            Set<Long> ids = found.stream().map(PlaceDistance::getId).collect(Collectors.toSet());
            List<PlaceDistance> matching = placeRepository.findInterestCandidates(leg.latitude(), leg.longitude(),
                    searchRadius, categories, slot.searchTag(), interestTags, sea, INTEREST_CANDIDATE_LIMIT);
            if (matching != null) {
                matching.stream().filter(p -> ids.add(p.getId())).forEach(found::add);
            }
        }

        Map<Long, Place> places = loadPlaces(found);

        List<PlaceScorer.ScoredPlace> affordable = new ArrayList<>();
        List<PlaceScorer.ScoredPlace> overBudget = new ArrayList<>();
        Map<Long, Timing> timings = new HashMap<>();
        // Outdoor candidates with rain expected on arrival
        Set<Long> rainedOut = new HashSet<>();

        for (PlaceDistance candidate : found) {
            Place place = places.get(candidate.getId());
            if (place == null || used.contains(place.getId())
                    // Route legs are walked: stay on the same side of the Bosphorus
                    || !BosphorusSides.sameSide(leg.latitude(), leg.longitude(), place.getLatitude(), place.getLongitude())
                    || samePlaceAsChosen(place, leg.chosen())
                    // A take-away bakery or a kıraathane is not a stop (still listed in Explore)
                    || !PlaceSuitability.isStop(place)
                    // Lunch / dinner at a tea house or coffee shop is not a meal
                    || (slot.type() == StopType.LUNCH || slot.type() == StopType.DINNER)
                    && !PlaceSuitability.servesMeals(place)) {
                continue;
            }

            Timing timing = timing(slot, place, leg.clock(), candidate.getDistanceMeters());
            Boolean open = place.isOpenDuring(request.date().plusDays(timing.arrival() / 1440),
                    time(timing.arrival()), timing.duration());
            if (Boolean.FALSE.equals(open)) {
                continue;
            }

            // null = price unknown (e.g. OpenStreetMap places); the scorer handles it
            Integer cost = place.getEstimatedCost() == null ? null : place.getEstimatedCost() * request.partySize();

            WeatherContext weather = WeatherContext.at(forecast, time(timing.arrival()), request.assumeWet());
            if (weather.wet() && !place.isIndoor()) {
                rainedOut.add(place.getId());
            }
            PlaceScorer.ScoredPlace scored = scorer.score(new PlaceScorer.Candidate(
                    place,
                    candidate.getDistanceMeters(),
                    maxLegForScoring,
                    timing.walkingMinutes(),
                    leg.firstLeg(),
                    time(timing.arrival()),
                    weather,
                    request.interests(),
                    cost,
                    leg.allowance(),
                    open,
                    slot.type()
            ));
            // Look-ahead: stay close to where the rest of the day goes
            if (leg.anchor() != null && leg.anchorWeight() > 0) {
                double off = PopularRouteBuilder.meters(place.getLatitude(), place.getLongitude(),
                        leg.anchor()[0], leg.anchor()[1]);
                double penalty = off / maxLegForScoring * leg.anchorWeight();
                scored = new PlaceScorer.ScoredPlace(place, scored.score() - penalty, scored.fitScore(), scored.reasons());
            }

            timings.put(place.getId(), timing);
            // An unknown price cannot be shown to be over budget; the scorer already prefers known prices
            boolean fits = leg.allowance() == null || cost == null || cost <= leg.allowance() * 1.4;
            if (fits) {
                affordable.add(scored);
            } else {
                // Over budget: every 10 TL over the allowance costs one point, so the fallback stays cheap
                double overspend = cost - Math.max(0, leg.allowance());
                overBudget.add(new PlaceScorer.ScoredPlace(place, scored.score() - overspend / 10,
                        scored.fitScore() - overspend / 10, scored.reasons()));
            }
        }

        // Sightseeing: when some candidate matches the user's interests, choose among those only. An outdoor place in
        // the rain does not count as a match (a "nature" park must not beat an indoor museum when it rains)
        boolean interestMatched = false;
        if (slot.type() == StopType.SIGHTSEEING && slot.categories() == null) {
            List<PlaceScorer.ScoredPlace> matchingAffordable = affordable.stream()
                    .filter(s -> !rainedOut.contains(s.place().getId()))
                    .filter(s -> !InterestMatcher.matching(s.place(), request.interests()).isEmpty()
                            && request.interests().stream().anyMatch(InterestMatcher::known))
                    .toList();
            if (!matchingAffordable.isEmpty()) {
                affordable = matchingAffordable;
                interestMatched = true;
            }
        }

        Comparator<PlaceScorer.ScoredPlace> byScore = Comparator.comparingDouble(PlaceScorer.ScoredPlace::score);

        return new SearchOutcome(
                affordable.stream().max(byScore)
                        .map(s -> toStop(slot, s.place(), timings.get(s.place().getId()), s.reasons())),
                overBudget.stream().max(byScore)
                        .map(s -> toStop(slot, s.place(), timings.get(s.place().getId()), s.reasons())),
                interestMatched
        );
    }

    // "Kelebek Cafe" and "Kelebek Cafe ve Restaurant" 5 m apart are one place for a route
    static boolean samePlaceAsChosen(Place place, List<Place> chosen) {
        String name = OsmPlaceMapper.fold(place.getName());
        for (Place other : chosen) {
            if (PopularRouteBuilder.meters(place.getLatitude(), place.getLongitude(), other.getLatitude(),
                    other.getLongitude()) > SAME_PLACE_METERS) {
                continue;
            }
            String otherName = OsmPlaceMapper.fold(other.getName());
            if (PopularRouteBuilder.containedName(name, otherName, 4, 0.4)) {
                return true;
            }
        }
        return false;
    }

    private record SearchOutcome(Optional<PlannedStop> affordable, Optional<PlannedStop> overBudget,
                                 boolean interestMatched) {
    }

    private Optional<PlannedStop> planPinned(
            PlanningSlot slot,
            LegContext leg,
            PlanningRequest request,
            WeatherForecast forecast,
            List<String> notes
    ) {
        Optional<Place> found = placeRepository.findWithOpeningHoursById(slot.pinnedPlaceId());
        if (found.isEmpty()) {
            return Optional.empty();
        }

        Place place = found.get();
        // No longer a place we show (PlaceRealismFilter): look for another one
        if (place.isHidden()) {
            return Optional.empty();
        }
        Double distance = placeRepository.distanceTo(place.getId(), leg.latitude(), leg.longitude());
        Timing timing = timing(slot, place, leg.clock(), distance == null ? 0 : distance);

        Boolean open = place.isOpenDuring(request.date().plusDays(timing.arrival() / 1440),
                time(timing.arrival()), timing.duration());
        if (Boolean.FALSE.equals(open)) {
            notes.add(Texts.t(place.getDisplayName() + " yeni saatte kapalı olacağı için yerine başka bir mekan arandı.",
                    place.getDisplayName() + " will be closed at the new time, so another place was looked for instead."));
            return Optional.empty();
        }

        // Too far for how much the user wants to walk now (e.g. after "çok yorulduk")
        double maxPinnedLeg = request.walkingTolerance().getMaxLegMeters() * 2.0;
        if (distance != null && distance > maxPinnedLeg) {
            notes.add(Texts.t(place.getDisplayName() + " artık uzak kaldığı için daha yakın bir yerle değiştirildi.",
                    place.getDisplayName() + " is now too far away, so it was replaced with a closer place."));
            return Optional.empty();
        }

        WeatherContext weather = WeatherContext.at(forecast, time(timing.arrival()), request.assumeWet());
        if (weather.wet() && !place.isIndoor() && !slot.keepInRain()) {
            notes.add(Texts.t(place.getDisplayName() + " açık alan olduğu ve yağış beklendiği için değiştirildi.",
                    place.getDisplayName() + " was replaced because it is outdoors and rain is expected."));
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();
        reasons.add(Texts.t("Rotanda zaten vardı", "Already in your route"));
        if (Texts.english()) {
            reasons.add(String.format(Locale.ROOT, "%d m from %s (~%d min walk)",
                    Math.round(distance == null ? 0 : distance),
                    leg.firstLeg() ? "where you are" : "the previous stop", timing.walkingMinutes()));
        } else {
            reasons.add(String.format(Locale.ROOT, "%s %d m (~%d dk yürüme)",
                    leg.firstLeg() ? "Bulunduğun noktaya" : "Önceki durağa",
                    Math.round(distance == null ? 0 : distance), timing.walkingMinutes()));
        }

        return Optional.of(toStop(slot, place, timing, reasons));
    }

    // ================= ORDER =================

    /**
     * The flexible stops (sightseeing, coffee, dessert) between two meals in the order with the least walking, as long
     * as every stop is open on arrival, meals keep their times and the day still ends in time. Segments are small
     * (at most a handful of stops), so every order is tried.
     */
    List<Chosen> reorder(List<Chosen> chosen, PlanningRequest request, WeatherForecast forecast) {
        List<Chosen> best = chosen;
        double bestMeters = pathMeters(request, chosen);
        int from = 0;
        while (from < chosen.size()) {
            if (!FLEXIBLE.contains(chosen.get(from).slot().type())) {
                from++;
                continue;
            }
            int to = from;
            while (to < chosen.size() && FLEXIBLE.contains(chosen.get(to).slot().type())) {
                to++;
            }
            if (to - from >= 2 && to - from <= 6) {
                List<Chosen> segment = best.subList(from, to);
                for (List<Chosen> order : PopularRouteBuilder.permutations(segment)) {
                    List<Chosen> candidate = new ArrayList<>(best.subList(0, from));
                    candidate.addAll(order);
                    candidate.addAll(best.subList(to, best.size()));
                    double meters = pathMeters(request, candidate);
                    if (meters + MIN_REORDER_SAVING_METERS >= bestMeters) {
                        continue;
                    }
                    Optional<List<Chosen>> retimed = retime(candidate, request, forecast);
                    if (retimed.isPresent()) {
                        best = retimed.get();
                        bestMeters = meters;
                    }
                }
            }
            from = to;
        }
        return best;
    }

    // Straight-line walk from the start through the stops
    private static double pathMeters(PlanningRequest request, List<Chosen> stops) {
        double meters = 0;
        double lat = request.startLatitude();
        double lon = request.startLongitude();
        for (Chosen c : stops) {
            meters += PopularRouteBuilder.meters(lat, lon, c.stop().place().getLatitude(), c.stop().place().getLongitude());
            lat = c.stop().place().getLatitude();
            lon = c.stop().place().getLongitude();
        }
        return meters;
    }

    /**
     * The stops in this order with new times and legs; empty when a stop would be closed on arrival, a meal would
     * start more than MEAL_LATE_MINUTES after its time or the day would not end in time.
     */
    private Optional<List<Chosen>> retime(List<Chosen> order, PlanningRequest request, WeatherForecast forecast) {
        List<Chosen> result = new ArrayList<>();
        int clock = minutes(request.startTime());
        int endOfDay = endMinutes(request.startTime(), request.endTime());
        double lat = request.startLatitude();
        double lon = request.startLongitude();
        for (Chosen c : order) {
            Place place = c.stop().place();
            double meters = PopularRouteBuilder.meters(lat, lon, place.getLatitude(), place.getLongitude());
            Timing timing = timing(c.slot(), place, clock, meters);
            Integer target = upcomingTarget(c.slot().targetTime(), clock);
            if (c.slot().type().isMeal() && target != null && timing.arrival() > target + MEAL_LATE_MINUTES) {
                return Optional.empty();
            }
            Boolean open = place.isOpenDuring(request.date().plusDays(timing.arrival() / 1440),
                    time(timing.arrival()), timing.duration());
            if (Boolean.FALSE.equals(open) || timing.arrival() + timing.duration() > endOfDay) {
                return Optional.empty();
            }
            // Outdoors in the rain at the new time: keep the original order instead
            if (!place.isIndoor() && WeatherContext.at(forecast, time(timing.arrival()), request.assumeWet()).wet()
                    && !WeatherContext.at(forecast, c.stop().start(), request.assumeWet()).wet()) {
                return Optional.empty();
            }
            // The "X m (~N dk yürüme)" reason describes the new leg
            List<String> reasons = new ArrayList<>(c.stop().reasons());
            int index = firstDistanceLine(reasons);
            if (index >= 0) {
                reasons.set(index, PlaceScorer.distanceReason(meters, timing.walkingMinutes(), result.isEmpty()));
            }
            PlannedStop stop = new PlannedStop(place, c.stop().type(), time(timing.arrival()),
                    time(timing.arrival() + timing.duration()), timing.distanceMeters(), timing.walkingMinutes(), reasons);
            result.add(new Chosen(c.slotIndex(), c.slot(), stop));
            clock = timing.arrival() + timing.duration();
            lat = place.getLatitude();
            lon = place.getLongitude();
        }
        return Optional.of(result);
    }

    // The scorer's "X m (~N dk yürüme)" line
    private static int firstDistanceLine(List<String> reasons) {
        for (int i = 0; i < reasons.size(); i++) {
            String r = reasons.get(i);
            if (r.startsWith("Başlangıç noktana ") || r.startsWith("Önceki durağa ")
                    || r.matches("\\d+ m from (your starting point|the previous stop) .*")) {
                return i;
            }
        }
        return -1;
    }

    // ================= NOTES =================

    /**
     * Which interests shaped the plan ("Deniz ve doğa tercihine göre seçilen duraklar: Moda Sahili, Kalamış Parkı.")
     * and which could not be matched in the area ("Bu bölgede deniz kenarı mekan bulunamadı.").
     */
    static List<String> interestNotes(List<PlannedStop> stops, List<String> interests, boolean rainySights) {
        List<String> known = interests.stream().filter(InterestMatcher::known).toList();
        if (known.isEmpty() || stops.isEmpty()) {
            return List.of();
        }
        List<String> used = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String interest : known) {
            List<String> matched = stops.stream()
                    .filter(s -> InterestMatcher.matches(s.place(), interest))
                    .map(s -> s.place().getDisplayName())
                    .toList();
            if (matched.isEmpty()) {
                missing.add(interest);
            } else {
                used.add(interest);
                matched.stream().filter(n -> !names.contains(n)).forEach(names::add);
            }
        }
        List<String> notes = new ArrayList<>();
        if (!used.isEmpty()) {
            String labels = labels(used);
            notes.add(Texts.t(capitalize(labels) + " tercihine göre seçilen duraklar: " + String.join(", ", names) + ".",
                    "Chosen for your " + labels + " interest" + (used.size() > 1 ? "s" : "") + ": "
                            + String.join(", ", names) + "."));
        }
        List<String> rainedOut = rainySights
                ? missing.stream().filter(OUTDOOR_INTERESTS::contains).toList() : List.of();
        if (!rainedOut.isEmpty()) {
            String labels = labels(rainedOut);
            notes.add(Texts.t("Yağış beklendiği için " + labels + " tercihine uyan açık alanlar yerine kapalı mekan seçildi.",
                    "Rain is expected, so an indoor place was chosen instead of outdoor ones for your " + labels
                            + " interest" + (rainedOut.size() > 1 ? "s" : "") + "."));
        }
        for (String interest : missing) {
            if (rainedOut.contains(interest)) {
                continue;
            }
            notes.add("sea".equals(interest)
                    ? Texts.t("Bu bölgede deniz kenarı mekan bulunamadı.", "No seaside place was found in this area.")
                    : Texts.t("Bu bölgede " + Interests.label(interest) + " tercihine uygun mekan bulunamadı.",
                    "No place matching your " + Interests.label(interest) + " interest was found in this area."));
        }
        return notes;
    }

    // "deniz ve doğa", "deniz, doğa ve uygun fiyat" / "sea and nature"
    static String labels(List<String> interests) {
        List<String> labels = interests.stream().map(Interests::label).toList();
        if (labels.size() <= 1) {
            return labels.isEmpty() ? "" : labels.getFirst();
        }
        return String.join(", ", labels.subList(0, labels.size() - 1)) + Texts.t(" ve ", " and ") + labels.getLast();
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Texts.locale()) + text.substring(1);
    }

    // ================= HELPERS =================

    private Timing timing(PlanningSlot slot, Place place, int clock, double distanceMeters) {
        int walkingMinutes = walkingMinutes(distanceMeters);
        int earliest = clock + walkingMinutes;

        int arrival = earliest;
        Integer target = upcomingTarget(slot.targetTime(), clock);
        if (target != null) {
            int flex = slot.exactTime() ? 0 : slot.type().isMeal() ? MEAL_FLEX_MINUTES : OTHER_FLEX_MINUTES;
            arrival = Math.max(earliest, target - flex);
            if (!slot.exactTime() && FLEXIBLE.contains(slot.type())) {
                arrival = Math.min(arrival, earliest + FLEXIBLE_MAX_WAIT_MINUTES);
            }
        }
        // Round to 5 minutes so times look like a human made the plan
        arrival = (int) (Math.ceil(arrival / 5.0) * 5);

        int duration = slot.durationMinutes() != null ? slot.durationMinutes()
                : place.getAvgVisitMinutes() != null ? place.getAvgVisitMinutes()
                : slot.type().getDefaultMinutes();

        return new Timing(arrival, duration, (int) Math.round(distanceMeters), walkingMinutes);
    }

    private PlannedStop toStop(PlanningSlot slot, Place place, Timing timing, List<String> reasons) {
        return new PlannedStop(
                place,
                slot.type(),
                time(timing.arrival()),
                time(timing.arrival() + timing.duration()),
                timing.distanceMeters(),
                timing.walkingMinutes(),
                reasons
        );
    }

    private Map<Long, Place> loadPlaces(List<PlaceDistance> found) {
        if (found.isEmpty()) {
            return Map.of();
        }
        return placeRepository.findByIdIn(found.stream().map(PlaceDistance::getId).toList()).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Place::getId, Function.identity(), (a, b) -> a));
    }

    static String unknownPriceNote(long count) {
        return Texts.t(count + " durağın fiyat bilgisi yok; toplam tahmine dahil edilmedi.",
                count == 1 ? "1 stop has no price info; it is not included in the estimated total."
                        : count + " stops have no price info; they are not included in the estimated total.");
    }

    public static int walkingMinutes(double distanceMeters) {
        return (int) Math.ceil(distanceMeters * DETOUR_FACTOR / WALKING_METERS_PER_MINUTE);
    }

    private static int minutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static LocalTime time(int dayMinutes) {
        int m = Math.floorMod(dayMinutes, 1440);
        return LocalTime.of(m / 60, m % 60);
    }

    // End time before start time (e.g. 10:00 -> 01:00) means the plan goes past midnight
    private static int endMinutes(LocalTime start, LocalTime end) {
        int e = minutes(end);
        return e <= minutes(start) ? e + 1440 : e;
    }

    /**
     * The slot's target on the plan's timeline, or null if it already passed.
     * A breakfast asked for at 12:45 is planned now, not tomorrow at 09:30.
     * Only targets up to 3 hours after midnight wrap to the next day (late plans).
     */
    static Integer upcomingTarget(LocalTime targetTime, int clock) {
        if (targetTime == null) {
            return null;
        }
        int target = minutes(targetTime) + (clock / 1440) * 1440;
        if (target >= clock - 180) {
            return target;
        }
        int nextDay = target + 1440;
        return nextDay - clock <= 180 ? nextDay : null;
    }

    // Places a time of day on the plan's timeline, at or after the reference minute
    private static int toDayMinutes(LocalTime time, int reference) {
        int m = minutes(time);
        while (m < reference) {
            m += 1440;
        }
        return m;
    }

    /**
     * @param anchor       where the rest of the day goes (look-ahead); null = no pull
     * @param chosen       places already in the plan (a second entry of one of them is skipped)
     */
    private record LegContext(double latitude, double longitude, int clock, boolean firstLeg, Double allowance,
                              double[] anchor, double anchorWeight, List<Place> chosen) {
    }

    private record Timing(int arrival, int duration, int distanceMeters, int walkingMinutes) {
    }

    // A planned stop with the slot it fills (slotIndex: its position in the request)
    record Chosen(int slotIndex, PlanningSlot slot, PlannedStop stop) {
    }

    record Pass(List<Chosen> chosen, List<String> notes) {
    }
}

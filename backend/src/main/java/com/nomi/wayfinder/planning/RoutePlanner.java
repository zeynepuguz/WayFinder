package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.weather.WeatherForecast;
import com.nomi.wayfinder.weather.WeatherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a day plan stop by stop (greedy). For each slot it asks PostGIS for nearby candidates,
 * removes places that are closed / already used / far over budget, scores the rest and picks the best.
 *
 * Deterministic on purpose: the LLM never decides places, prices or distances; it only
 * turns user text into a request and explains the result.
 */
@Service
public class RoutePlanner {

    // Straight-line meters are shorter than real streets
    static final double DETOUR_FACTOR = 1.3;
    static final double WALKING_METERS_PER_MINUTE = 75;
    // When the first search finds nothing, look this much further once
    static final double EXPANDED_SEARCH_FACTOR = 1.8;
    static final int CANDIDATE_LIMIT = 40;
    // How early before its target time a stop may start (a meal should not start hours early)
    static final int MEAL_FLEX_MINUTES = 30;
    static final int OTHER_FLEX_MINUTES = 90;

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
            notes.add("Hava durumu bilgisi alınamadı; plan hava durumu dikkate alınmadan oluşturuldu.");
        }

        String advice = forecast
                .map(f -> weatherService.advice(f, request.startTime(), request.endTime()))
                .orElse(request.assumeWet() ? "Yağmur nedeniyle kapalı mekanlara öncelik verdim." : null);

        List<PlannedStop> stops = new ArrayList<>();
        Set<Long> used = new HashSet<>(request.excludedPlaceIds());

        double latitude = request.startLatitude();
        double longitude = request.startLongitude();
        // Minutes from the start day's midnight; can go past 24:00 for late plans
        int clock = minutes(request.startTime());
        int endOfDay = endMinutes(request.startTime(), request.endTime());

        Integer remainingBudget = request.budget();
        double remainingWeight = request.slots().stream().mapToDouble(s -> s.type().getBudgetWeight()).sum();

        for (PlanningSlot slot : request.slots()) {
            Double allowance = null;
            if (remainingBudget != null && remainingWeight > 0) {
                allowance = remainingBudget * (slot.type().getBudgetWeight() / remainingWeight);
            }
            remainingWeight -= slot.type().getBudgetWeight();

            LegContext leg = new LegContext(latitude, longitude, clock, stops.isEmpty(), allowance);
            // Notes about this stop only count if the stop ends up in the plan
            List<String> slotNotes = new ArrayList<>();
            Optional<PlannedStop> planned = planSlot(slot, leg, request, forecast.orElse(null), used, slotNotes);

            if (planned.isEmpty()) {
                notes.addAll(slotNotes);
                continue;
            }

            PlannedStop stop = planned.get();
            int stopEnd = toDayMinutes(stop.end(), clock);
            if (stopEnd > endOfDay) {
                notes.add(slot.type().getLabel() + " gün sonuna sığmadığı için eklenmedi.");
                continue;
            }

            notes.addAll(slotNotes);
            stops.add(stop);
            used.add(stop.place().getId());
            latitude = stop.place().getLatitude();
            longitude = stop.place().getLongitude();
            clock = stopEnd;

            if (remainingBudget != null) {
                remainingBudget -= stop.totalCost(request.partySize());
            }
        }

        if (remainingBudget != null && remainingBudget < 0) {
            notes.add("Tahmini harcama bütçeyi yaklaşık " + (-remainingBudget) + " TL aşıyor.");
        }

        return new PlanResult(stops, notes, forecast, advice);
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

        // 1) nearby and within budget
        SearchOutcome near = search(slot, leg, request, forecast, used, maxLeg, maxLeg);
        if (near.affordable().isPresent()) {
            return near.affordable();
        }

        // 2) a bit further but within budget
        SearchOutcome far = search(slot, leg, request, forecast, used, maxLeg * EXPANDED_SEARCH_FACTOR, maxLeg);
        if (far.affordable().isPresent()) {
            notes.add(slot.type().getLabel() + " için yakında uygun yer bulunamadı; biraz daha uzaktaki "
                    + far.affordable().get().place().getName() + " seçildi.");
            return far.affordable();
        }

        // 3) over budget: best overall score (the score already penalizes cost)
        Optional<PlannedStop> fallback = near.overBudget().isPresent() ? near.overBudget() : far.overBudget();
        if (fallback.isEmpty()) {
            notes.add(slot.type().getLabel() + " için bu saatte açık ve uygun bir mekan bulunamadı.");
        }
        return fallback;
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
        StopType type = slot.type();
        List<String> categories = type.getCategories().stream().map(PlaceCategory::name).toList();

        List<PlaceDistance> found = placeRepository.findCandidates(
                leg.latitude(), leg.longitude(), searchRadius, categories, type.getMatchingTag(), CANDIDATE_LIMIT);

        Map<Long, Place> places = loadPlaces(found);

        List<PlaceScorer.ScoredPlace> affordable = new ArrayList<>();
        List<PlaceScorer.ScoredPlace> overBudget = new ArrayList<>();
        Map<Long, Timing> timings = new HashMap<>();

        for (PlaceDistance candidate : found) {
            Place place = places.get(candidate.getId());
            if (place == null || used.contains(place.getId())) {
                continue;
            }

            Timing timing = timing(slot, place, leg.clock(), candidate.getDistanceMeters());
            Boolean open = place.isOpenDuring(request.date().plusDays(timing.arrival() / 1440),
                    time(timing.arrival()), timing.duration());
            if (Boolean.FALSE.equals(open)) {
                continue;
            }

            int cost = place.getEstimatedCost() == null ? 0 : place.getEstimatedCost() * request.partySize();

            PlaceScorer.ScoredPlace scored = scorer.score(new PlaceScorer.Candidate(
                    place,
                    candidate.getDistanceMeters(),
                    maxLegForScoring,
                    timing.walkingMinutes(),
                    leg.firstLeg(),
                    time(timing.arrival()),
                    WeatherContext.at(forecast, time(timing.arrival()), request.assumeWet()),
                    request.interests(),
                    cost,
                    leg.allowance(),
                    open
            ));

            timings.put(place.getId(), timing);
            boolean fits = leg.allowance() == null || cost <= leg.allowance() * 1.4;
            if (fits) {
                affordable.add(scored);
            } else {
                // Over budget: every 10 TL over the allowance costs one point, so the fallback stays cheap
                double overspend = cost - Math.max(0, leg.allowance());
                overBudget.add(new PlaceScorer.ScoredPlace(place, scored.score() - overspend / 10, scored.reasons()));
            }
        }

        Comparator<PlaceScorer.ScoredPlace> byScore = Comparator.comparingDouble(PlaceScorer.ScoredPlace::score);

        return new SearchOutcome(
                affordable.stream().max(byScore)
                        .map(s -> toStop(slot, s.place(), timings.get(s.place().getId()), s.reasons())),
                overBudget.stream().max(byScore)
                        .map(s -> toStop(slot, s.place(), timings.get(s.place().getId()), s.reasons()))
        );
    }

    private record SearchOutcome(Optional<PlannedStop> affordable, Optional<PlannedStop> overBudget) {
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
        Double distance = placeRepository.distanceTo(place.getId(), leg.latitude(), leg.longitude());
        Timing timing = timing(slot, place, leg.clock(), distance == null ? 0 : distance);

        Boolean open = place.isOpenDuring(request.date().plusDays(timing.arrival() / 1440),
                time(timing.arrival()), timing.duration());
        if (Boolean.FALSE.equals(open)) {
            notes.add(place.getName() + " yeni saatte kapalı olacağı için yerine başka bir mekan arandı.");
            return Optional.empty();
        }

        // Too far for how much the user wants to walk now (e.g. after "çok yorulduk")
        double maxPinnedLeg = request.walkingTolerance().getMaxLegMeters() * 2.0;
        if (distance != null && distance > maxPinnedLeg) {
            notes.add(place.getName() + " artık uzak kaldığı için daha yakın bir yerle değiştirildi.");
            return Optional.empty();
        }

        WeatherContext weather = WeatherContext.at(forecast, time(timing.arrival()), request.assumeWet());
        if (weather.wet() && !place.isIndoor()) {
            notes.add(place.getName() + " açık alan olduğu ve yağış beklendiği için değiştirildi.");
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();
        reasons.add("Rotanda zaten vardı");
        reasons.add(String.format(Locale.ROOT, "%s %d m (~%d dk yürüme)",
                leg.firstLeg() ? "Bulunduğun noktaya" : "Önceki durağa",
                Math.round(distance == null ? 0 : distance), timing.walkingMinutes()));

        return Optional.of(toStop(slot, place, timing, reasons));
    }

    private Timing timing(PlanningSlot slot, Place place, int clock, double distanceMeters) {
        int walkingMinutes = walkingMinutes(distanceMeters);
        int earliest = clock + walkingMinutes;

        int arrival = earliest;
        Integer target = upcomingTarget(slot.targetTime(), clock);
        if (target != null) {
            int flex = slot.exactTime() ? 0 : slot.type().isMeal() ? MEAL_FLEX_MINUTES : OTHER_FLEX_MINUTES;
            arrival = Math.max(earliest, target - flex);
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
                .collect(Collectors.toMap(Place::getId, Function.identity()));
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

    private record LegContext(double latitude, double longitude, int clock, boolean firstLeg, Double allowance) {
    }

    private record Timing(int arrival, int duration, int distanceMeters, int walkingMinutes) {
    }
}

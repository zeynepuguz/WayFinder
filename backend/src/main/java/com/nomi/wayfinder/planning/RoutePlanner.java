package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.i18n.Texts;
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
            notes.add(Texts.t("Hava durumu bilgisi alınamadı; plan hava durumu dikkate alınmadan oluşturuldu.",
                    "Weather information was not available; the plan was made without taking the weather into account."));
        }

        String advice = forecast
                .map(f -> weatherService.advice(f, request.startTime(), request.endTime()))
                .orElse(request.assumeWet()
                        ? Texts.t("Yağmur nedeniyle kapalı mekanlara öncelik verdim.",
                        "I prioritized indoor places because of the rain.")
                        : null);

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
                notes.add(Texts.t(slot.type().getLabel() + " gün sonuna sığmadığı için eklenmedi.",
                        slot.type().getLabel() + " was not added because it did not fit before the end of the day."));
                continue;
            }

            // The scorer only penalizes outdoor places in rain; say so when nothing indoor was left
            if (!stop.place().isIndoor()
                    && WeatherContext.at(forecast.orElse(null), stop.start(), request.assumeWet()).wet()) {
                slotNotes.add(Texts.t(
                        stop.place().getName() + " açık alan ve o saatte yağış bekleniyor; yakında uygun kapalı bir "
                                + slot.type().getLabel().toLowerCase(java.util.Locale.forLanguageTag("tr")) + " mekanı bulamadım.",
                        stop.place().getName() + " is outdoors and rain is expected at that time; I could not find a suitable indoor "
                                + Texts.lower(slot.type().getLabel()) + " place nearby."));
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
            notes.add(Texts.t("Tahmini harcama bütçeyi yaklaşık " + (-remainingBudget) + " TL aşıyor.",
                    "Estimated spending is about " + (-remainingBudget) + " TL over the budget."));
        }

        // Unknown prices are left out of the totals (not counted as free); say so
        long unknownPrice = stops.stream().filter(s -> s.place().getEstimatedCost() == null).count();
        if (unknownPrice > 0) {
            notes.add(unknownPriceNote(unknownPrice));
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
            notes.add(Texts.t(slot.type().getLabel() + " için yakında uygun yer bulunamadı; biraz daha uzaktaki "
                            + far.affordable().get().place().getName() + " seçildi.",
                    "No suitable " + Texts.lower(slot.type().getLabel()) + " place was found nearby; "
                            + far.affordable().get().place().getName() + ", a little further away, was chosen."));
            return far.affordable();
        }

        // 3) over budget: best overall score (the score already penalizes cost)
        Optional<PlannedStop> fallback = near.overBudget().isPresent() ? near.overBudget() : far.overBudget();
        if (fallback.isEmpty()) {
            notes.add(Texts.t(slot.type().getLabel() + " için bu saatte açık ve uygun bir mekan bulunamadı.",
                    "No suitable " + Texts.lower(slot.type().getLabel()) + " place is open at this time."));
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
            if (place == null || used.contains(place.getId())
                    // Route legs are walked: stay on the same side of the Bosphorus
                    || !BosphorusSides.sameSide(leg.latitude(), leg.longitude(), place.getLatitude(), place.getLongitude())) {
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
            notes.add(Texts.t(place.getName() + " yeni saatte kapalı olacağı için yerine başka bir mekan arandı.",
                    place.getName() + " will be closed at the new time, so another place was looked for instead."));
            return Optional.empty();
        }

        // Too far for how much the user wants to walk now (e.g. after "çok yorulduk")
        double maxPinnedLeg = request.walkingTolerance().getMaxLegMeters() * 2.0;
        if (distance != null && distance > maxPinnedLeg) {
            notes.add(Texts.t(place.getName() + " artık uzak kaldığı için daha yakın bir yerle değiştirildi.",
                    place.getName() + " is now too far away, so it was replaced with a closer place."));
            return Optional.empty();
        }

        WeatherContext weather = WeatherContext.at(forecast, time(timing.arrival()), request.assumeWet());
        if (weather.wet() && !place.isIndoor()) {
            notes.add(Texts.t(place.getName() + " açık alan olduğu ve yağış beklendiği için değiştirildi.",
                    place.getName() + " was replaced because it is outdoors and rain is expected."));
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

    private record LegContext(double latitude, double longitude, int clock, boolean firstLeg, Double allowance) {
    }

    private record Timing(int arrival, int duration, int distanceMeters, int walkingMinutes) {
    }
}

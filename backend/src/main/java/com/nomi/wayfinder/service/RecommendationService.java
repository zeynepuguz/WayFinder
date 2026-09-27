package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.NearbyPlaceResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.UserPreferences;
import com.nomi.wayfinder.entity.WalkingTolerance;
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
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);

        WalkingTolerance tolerance = WalkingTolerance.MEDIUM;
        List<String> interests = List.of();
        if (userId != null) {
            UserPreferences preferences = userService.getPreferences(userId);
            tolerance = preferences.getWalkingTolerance();
            interests = preferences.getInterests();
        }

        WeatherForecast forecast = weatherService.getForecast(latitude, longitude, today).orElse(null);
        double radius = tolerance.getMaxLegMeters() * 1.5;

        List<PlaceDistance> found = placeRepository.findCandidates(
                latitude, longitude, radius,
                type.getCategories().stream().map(PlaceCategory::name).toList(),
                type.getMatchingTag(), 40);

        Map<Long, Place> places = placeService.loadPlaces(found);
        List<Recommendation> results = new ArrayList<>();

        for (PlaceDistance candidate : found) {
            Place place = places.get(candidate.getId());
            int walking = RoutePlanner.walkingMinutes(candidate.getDistanceMeters());
            LocalTime arrival = now.plusMinutes(walking);
            int duration = place.getAvgVisitMinutes() != null ? place.getAvgVisitMinutes() : type.getDefaultMinutes();

            Boolean open = place.isOpenDuring(today, arrival, duration);
            if (Boolean.FALSE.equals(open)) {
                continue;
            }

            PlaceScorer.ScoredPlace scored = scorer.score(new PlaceScorer.Candidate(
                    place, candidate.getDistanceMeters(), radius, walking, true, arrival,
                    WeatherContext.at(forecast, arrival, false), interests,
                    place.getEstimatedCost() == null ? 0 : place.getEstimatedCost(), null, open));

            results.add(new Recommendation(
                    placeMapper.toNearbyResponse(place, candidate.getDistanceMeters()),
                    type,
                    Math.round(scored.score() * 10) / 10.0,
                    scored.reasons()));
        }

        return results.stream()
                .sorted(Comparator.comparingDouble(Recommendation::score).reversed())
                .limit(limit)
                .toList();
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

    public record Recommendation(NearbyPlaceResponse place, StopType type, double score, List<String> reasons) {
    }
}

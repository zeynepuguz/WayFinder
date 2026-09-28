package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.PlaceImage;
import com.nomi.wayfinder.dto.RouteDtos.*;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.RouteStop;
import com.nomi.wayfinder.entity.StopStatus;
import org.springframework.stereotype.Component;

@Component
public class RouteMapper {

    public RouteResponse toResponse(Route route) {
        var stops = route.getStops().stream().map(this::toStopResponse).toList();

        return new RouteResponse(
                route.getId(),
                route.getTitle(),
                route.getDate(),
                route.getStatus(),
                route.isSaved(),
                route.getStartLocation().getY(),
                route.getStartLocation().getX(),
                RouteStartService.label(route.getStartKind(), route.getStartLabel()),
                route.getStartTime(),
                route.getEndTime(),
                route.getPartySize(),
                route.getBudget(),
                totalCost(route),
                route.getStops().stream().mapToInt(RouteStop::getDistanceFromPreviousMeters).sum(),
                route.getStops().stream().mapToInt(RouteStop::getWalkingMinutes).sum(),
                route.getWalkingTolerance(),
                route.getInterests(),
                new WeatherSnapshot(route.getWeatherCondition(), route.getWeatherTemperature(), route.getWeatherAdvice()),
                route.getNotes(),
                stops,
                route.getCreatedAt(),
                route.getUpdatedAt()
        );
    }

    public RouteSummary toSummary(Route route) {
        return new RouteSummary(
                route.getId(),
                route.getTitle(),
                route.getDate(),
                route.getStatus(),
                route.isSaved(),
                route.getStops().size(),
                totalCost(route),
                route.getCreatedAt()
        );
    }

    // Skipped stops cost nothing; stops with unknown price (null) are left out, not counted as free
    public int totalCost(Route route) {
        return route.getStops().stream()
                .filter(s -> s.getStatus() != StopStatus.SKIPPED)
                .mapToInt(s -> s.getPlace().getEstimatedCost() == null ? 0
                        : s.getPlace().getEstimatedCost() * route.getPartySize())
                .sum();
    }

    private StopResponse toStopResponse(RouteStop stop) {
        Place place = stop.getPlace();

        return new StopResponse(
                stop.getId(),
                stop.getPosition(),
                stop.getStopType(),
                stop.getStopType().getLabel(),
                stop.getPlannedStart(),
                stop.getPlannedEnd(),
                stop.getDistanceFromPreviousMeters(),
                stop.getWalkingMinutes(),
                stop.getReasons(),
                stop.getStatus(),
                new StopPlace(
                        place.getId(),
                        place.getDisplayName(),
                        place.getCategory(),
                        place.getAddress(),
                        place.getNeighborhood(),
                        place.getLatitude(),
                        place.getLongitude(),
                        place.getEstimatedCost(),
                        place.getRating(),
                        place.isIndoor(),
                        PlaceImage.of(place)
                )
        );
    }
}

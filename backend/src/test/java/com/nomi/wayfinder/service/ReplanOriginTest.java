package com.nomi.wayfinder.service;

import com.nomi.wayfinder.entity.*;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

// A re-plan starts on the route, never at a user who edits it from another city
class ReplanOriginTest {

    // Kocatepe Camii, Ankara (the route's start) and a stop next to it
    private static final double ANKARA_LAT = 39.9166, ANKARA_LON = 32.8604;
    // Çayırova, Kocaeli (where the user is while editing)
    private static final double KOCAELI_LAT = 40.8260, KOCAELI_LON = 29.3770;

    @Test
    void editingFromAnotherCityKeepsTheRoutesOwnStart() {
        Route route = ankaraRoute(stop(39.9200, 32.8540, StopStatus.PLANNED, "11:00"));

        double[] origin = RouteService.replanOrigin(route, List.of(), KOCAELI_LAT, KOCAELI_LON);

        assertThat(origin).containsExactly(ANKARA_LAT, ANKARA_LON);
    }

    @Test
    void editingFromAnotherCityContinuesFromTheLastVisitedStop() {
        RouteStop first = stop(39.9200, 32.8540, StopStatus.VISITED, "10:00");
        RouteStop second = stop(39.9250, 32.8500, StopStatus.VISITED, "12:00");
        RouteStop skipped = stop(39.9300, 32.8400, StopStatus.SKIPPED, "13:00");
        Route route = ankaraRoute(first, second, skipped);

        double[] origin = RouteService.replanOrigin(route, List.of(first, second, skipped), KOCAELI_LAT, KOCAELI_LON);

        assertThat(origin).containsExactly(39.9250, 32.8500);
    }

    @Test
    void onTheRouteTheUsersPositionIsUsed() {
        // ~1 km from the second stop, ~2.5 km from the start... still on the trip
        Route route = ankaraRoute(stop(39.9400, 32.8600, StopStatus.PLANNED, "11:00"));

        double[] origin = RouteService.replanOrigin(route, List.of(), 39.9480, 32.8650);

        assertThat(origin).containsExactly(39.9480, 32.8650);
    }

    @Test
    void withoutAPositionTheRoutesStartIsUsed() {
        Route route = ankaraRoute(stop(39.9200, 32.8540, StopStatus.PLANNED, "11:00"));

        assertThat(RouteService.replanOrigin(route, List.of(), null, null)).containsExactly(ANKARA_LAT, ANKARA_LON);
    }

    private static Route ankaraRoute(RouteStop... stops) {
        Route route = new Route();
        route.setStartLocation(ANKARA_LAT, ANKARA_LON);
        route.getStops().addAll(List.of(stops));
        return route;
    }

    private static RouteStop stop(double lat, double lon, StopStatus status, String end) {
        Place place = place(1, "Durak", PlaceCategory.CAFE, true, 4.5, 100);
        place.setCoordinates(lat, lon);
        RouteStop stop = new RouteStop();
        stop.setPlace(place);
        stop.setStatus(status);
        stop.setPlannedEnd(LocalTime.parse(end));
        return stop;
    }
}

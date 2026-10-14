package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.planning.RouteSimilarity.Shape;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import static com.nomi.wayfinder.entity.StopType.*;
import static org.assertj.core.api.Assertions.assertThat;

// Does a liked route answer a new request? (planning/RouteSimilarity)
class RouteSimilarityTest {

    // Kadıköy, near the pier
    private static final double LAT = 40.9905;
    private static final double LON = 29.0237;

    private static Shape shape(double lat, double lon, Collection<StopType> stops, Collection<String> interests, Integer budget,
                               WalkingTolerance walking, LocalTime start) {
        return new Shape(lat, lon, stops, interests, budget, walking, start);
    }

    private static final Shape WANTED = shape(LAT, LON, List.of(BREAKFAST, SIGHTSEEING, LUNCH),
            List.of("history", "sea"), 800, WalkingTolerance.MEDIUM, LocalTime.of(10, 0));

    @Test
    void theSameKindOfDayNearbyMatches() {
        Shape liked = shape(LAT + 0.005, LON, List.of(BREAKFAST, SIGHTSEEING, SIGHTSEEING, LUNCH),
                List.of("history", "sea"), 600, WalkingTolerance.LOW, LocalTime.of(9, 0));

        assertThat(RouteSimilarity.match(WANTED, liked)).hasValue(1.0);
    }

    @Test
    void tooFarAwayIsAnotherPlace() {
        // ~2.2 km north
        Shape liked = shape(LAT + 0.02, LON, WANTED.stops(), WANTED.interests(), null, null, LocalTime.of(10, 0));

        assertThat(RouteSimilarity.match(WANTED, liked)).isEmpty();
    }

    @Test
    void otherKindsOfStopsAreAnotherDay() {
        Shape liked = shape(LAT, LON, List.of(BREAKFAST, SIGHTSEEING, DINNER), WANTED.interests(), null, null,
                LocalTime.of(10, 0));

        assertThat(RouteSimilarity.match(WANTED, liked)).isEmpty();
    }

    @Test
    void anEveningRouteDoesNotAnswerAMorningRequest() {
        Shape liked = shape(LAT, LON, WANTED.stops(), WANTED.interests(), null, null, LocalTime.of(14, 30));

        assertThat(RouteSimilarity.match(WANTED, liked)).isEmpty();
    }

    @Test
    void neverMoreWalkingOrMoreCostThanAsked() {
        Shape walksMore = shape(LAT, LON, WANTED.stops(), WANTED.interests(), null, WalkingTolerance.HIGH,
                LocalTime.of(10, 0));
        Shape costsMore = shape(LAT, LON, WANTED.stops(), WANTED.interests(), 1200, null, LocalTime.of(10, 0));

        assertThat(RouteSimilarity.match(WANTED, walksMore)).isEmpty();
        assertThat(RouteSimilarity.match(WANTED, costsMore)).isEmpty();
    }

    @Test
    void interestsMustOverlapAtLeastHalf() {
        Shape half = shape(LAT, LON, WANTED.stops(), List.of("history", "art"), null, null, LocalTime.of(10, 0));
        Shape apart = shape(LAT, LON, WANTED.stops(), List.of("art", "music"), null, null, LocalTime.of(10, 0));

        assertThat(RouteSimilarity.match(WANTED, half)).isEmpty();
        assertThat(RouteSimilarity.match(WANTED, apart)).isEmpty();
        assertThat(RouteSimilarity.overlap(List.of("history", "sea"), List.of("history", "sea", "art")))
                .isEqualTo(2.0 / 3);
        assertThat(RouteSimilarity.overlap(List.of(), List.of())).isEqualTo(1.0);
    }
}

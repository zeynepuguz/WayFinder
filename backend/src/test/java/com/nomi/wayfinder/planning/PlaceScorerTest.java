package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceScorerTest {

    private static final WeatherContext RAIN = new WeatherContext(true, false, false, false, true);
    private static final WeatherContext HOT_NOON = new WeatherContext(false, true, false, false, true);
    private static final WeatherContext NICE = new WeatherContext(false, false, false, false, true);

    private final PlaceScorer scorer = new PlaceScorer();

    @Test
    void rainMakesIndoorPlaceWinOverBetterRatedOutdoorPlace() {
        Place outdoor = place(1, "Sahil", PlaceCategory.PARK, false, 4.8, 0);
        Place indoor = place(2, "Müze", PlaceCategory.MUSEUM, true, 4.3, 0);

        double outdoorScore = score(outdoor, RAIN, LocalTime.of(14, 0), 300, null).score();
        PlaceScorer.ScoredPlace indoorResult = score(indoor, RAIN, LocalTime.of(14, 0), 300, null);

        assertThat(indoorResult.score()).isGreaterThan(outdoorScore);
        assertThat(indoorResult.reasons()).anyMatch(r -> r.contains("kapalı mekan"));
    }

    @Test
    void heatPenalizesOutdoorPlaces() {
        Place outdoor = place(1, "Park", PlaceCategory.PARK, false, 4.5, 0);

        double hot = score(outdoor, HOT_NOON, LocalTime.of(13, 0), 300, null).score();
        double nice = score(outdoor, NICE, LocalTime.of(13, 0), 300, null).score();

        assertThat(hot).isLessThan(nice);
    }

    @Test
    void seaSideGetsEveningBonus() {
        Place sea = place(1, "Sahil", PlaceCategory.PARK, false, 4.5, 0, "sea");

        double evening = score(sea, NICE, LocalTime.of(19, 0), 300, null).score();
        double noon = score(sea, NICE, LocalTime.of(12, 0), 300, null).score();

        assertThat(evening).isGreaterThan(noon);
    }

    @Test
    void closerPlaceScoresHigherWhenEverythingElseIsEqual() {
        Place cafe = place(1, "Cafe", PlaceCategory.CAFE, true, 4.5, 100);

        double near = score(cafe, NICE, LocalTime.of(15, 0), 100, null).score();
        double far = score(cafe, NICE, LocalTime.of(15, 0), 1000, null).score();

        assertThat(near).isGreaterThan(far);
    }

    @Test
    void interestsAndBudgetAffectScore() {
        Place history = place(1, "Kilise", PlaceCategory.ATTRACTION, true, 4.5, 0, "history");
        Place other = place(2, "AVM", PlaceCategory.ATTRACTION, true, 4.5, 0, "shopping");

        assertThat(score(history, NICE, LocalTime.of(11, 0), 300, null).score())
                .isGreaterThan(score(other, NICE, LocalTime.of(11, 0), 300, null).score());

        Place cheap = place(3, "Ucuz", PlaceCategory.RESTAURANT, true, 4.5, 200);
        Place expensive = place(4, "Pahalı", PlaceCategory.RESTAURANT, true, 4.5, 1000);

        assertThat(score(cheap, NICE, LocalTime.of(13, 0), 300, 500.0).score())
                .isGreaterThan(score(expensive, NICE, LocalTime.of(13, 0), 300, 500.0).score());
    }

    @Test
    void usedUpBudgetPenalizesPaidPlacesNotFreeOnes() {
        Place paid = place(1, "Tatlıcı", PlaceCategory.DESSERT, true, 4.5, 300);
        Place free = place(2, "Park", PlaceCategory.PARK, true, 4.5, 0);

        double paidNoBudget = score(paid, NICE, LocalTime.of(18, 0), 300, null).score();
        double paidBudgetGone = score(paid, NICE, LocalTime.of(18, 0), 300, -100.0).score();
        double freeNoBudget = score(free, NICE, LocalTime.of(18, 0), 300, null).score();
        double freeBudgetGone = score(free, NICE, LocalTime.of(18, 0), 300, -100.0).score();

        assertThat(paidBudgetGone).isLessThan(paidNoBudget);
        assertThat(freeBudgetGone).isEqualTo(freeNoBudget);
    }

    private PlaceScorer.ScoredPlace score(Place place, WeatherContext weather, LocalTime arrival,
                                          double distance, Double allowance) {
        int cost = place.getEstimatedCost();
        return scorer.score(new PlaceScorer.Candidate(
                place, distance, 1200, RoutePlanner.walkingMinutes(distance), false, arrival, weather,
                List.of("history"), cost, allowance, true));
    }
}

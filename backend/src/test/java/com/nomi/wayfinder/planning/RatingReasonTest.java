package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static com.nomi.wayfinder.TestPlaces.place;
import static com.nomi.wayfinder.planning.RatingReason.*;
import static org.assertj.core.api.Assertions.assertThat;

// "Neden bu puanı verdin?" answers and how they reach the planner
class RatingReasonTest {

    private static final WeatherContext NICE = new WeatherContext(false, false, false, false, true);

    private final PlaceScorer scorer = new PlaceScorer();

    @Test
    void answersFitTheStars() {
        assertThat(forStars(5)).contains(TASTY, AFFORDABLE).doesNotContain(EXPENSIVE, NOT_TASTY);
        assertThat(forStars(1)).contains(EXPENSIVE, SLOW_SERVICE).doesNotContain(TASTY);
        assertThat(forStars(3)).contains(TASTY, EXPENSIVE);
    }

    @Test
    void aTagNeedsThreeUsersAndAThirdOfTheRaters() {
        assertThat(communityTags(Map.of(AFFORDABLE, 2), 2)).isEmpty();
        assertThat(communityTags(Map.of(AFFORDABLE, 3), 6)).containsExactly("budget");
        // Three of twelve raters is too few to speak for the place
        assertThat(communityTags(Map.of(AFFORDABLE, 3), 12)).isEmpty();
        // Answers without a tag ("Lezzetli") never become one
        assertThat(communityTags(Map.of(TASTY, 10), 10)).isEmpty();
    }

    @Test
    void oppositeAnswersCancelOut() {
        assertThat(communityTags(Map.of(AFFORDABLE, 4, EXPENSIVE, 3), 7)).isEmpty();
        assertThat(communityTags(Map.of(AFFORDABLE, 1, EXPENSIVE, 5), 6)).containsExactly("pricey");
        assertThat(communityTags(Map.of(QUIET, 5, CROWDED_NOISY, 1), 6)).containsExactly("quiet");
    }

    private static Place withCommunityTags(Place place, String... tags) {
        ReflectionTestUtils.setField(place, "communityTags", List.of(tags));
        return place;
    }

    private PlaceScorer.ScoredPlace score(Place place, List<String> interests) {
        return scorer.score(new PlaceScorer.Candidate(place, 300, 1200, 6, false, LocalTime.of(13, 0), NICE,
                interests, null, null, true, null, false));
    }

    @Test
    void whatUsersSaidMatchesInterestsAndSaysSo() {
        Place cheap = withCommunityTags(place(1, "Lokanta A", PlaceCategory.RESTAURANT, true, 4.0, 0), "budget");
        Place other = place(2, "Lokanta B", PlaceCategory.RESTAURANT, true, 4.0, 0);

        PlaceScorer.ScoredPlace result = score(cheap, List.of("budget"));

        assertThat(result.score()).isGreaterThan(score(other, List.of("budget")).score());
        assertThat(result.reasons()).contains("Nomi kullanıcılarına göre: uygun fiyat");
    }

    @Test
    void anImportedTagIsNotCreditedToUsers() {
        Place tagged = place(1, "Kafe", PlaceCategory.CAFE, true, 4.0, 0, "view");
        withCommunityTags(tagged, "view");

        assertThat(score(tagged, List.of("view")).reasons()).noneMatch(r -> r.startsWith("Nomi kullanıcılarına göre"));
    }

    @Test
    void aPlaceMostRatersFoundExpensiveDropsForABudgetDay() {
        Place pricey = withCommunityTags(place(1, "Lokanta A", PlaceCategory.RESTAURANT, true, 4.0, 0), "pricey");
        Place other = place(2, "Lokanta B", PlaceCategory.RESTAURANT, true, 4.0, 0);

        assertThat(score(other, List.of("budget")).score() - score(pricey, List.of("budget")).score())
                .isEqualTo(PlaceScorer.PRICEY_FOR_BUDGET_PENALTY);
        // Without "uygun fiyat" it does not matter
        assertThat(score(pricey, List.of()).score()).isEqualTo(score(other, List.of()).score());
    }

    @Test
    void theFamilyDayLooksForPlacesGoodWithChildren() {
        Place family = withCommunityTags(place(1, "Kafe", PlaceCategory.CAFE, true, 4.0, 0), "family");

        assertThat(DayTheme.FAMILY.interests(List.of())).contains("family");
        assertThat(InterestMatcher.matches(family, "family")).isTrue();
        assertThat(InterestMatcher.matchesImported(family, "family")).isFalse();
    }
}

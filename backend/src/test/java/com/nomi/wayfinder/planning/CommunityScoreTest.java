package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;

import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// What Nomi's users think of a place (stars, saves) and how it moves lists and the planner
class CommunityScoreTest {

    private static final WeatherContext NICE = new WeatherContext(false, false, false, false, true);

    private final PlaceScorer scorer = new PlaceScorer();

    private static Place rated(Place place, int sum, int count, int saves) {
        ReflectionTestUtils.setField(place, "userRatingSum", sum);
        ReflectionTestUtils.setField(place, "userRatingCount", count);
        ReflectionTestUtils.setField(place, "favoriteCount", saves);
        return place;
    }

    private PlaceScorer.ScoredPlace score(Place place, boolean savedByUser) {
        return scorer.score(new PlaceScorer.Candidate(place, 300, 1200, 6, false, LocalTime.of(13, 0), NICE,
                List.of(), null, null, true, null, savedByUser));
    }

    @Test
    void withoutVotesTheRatingIsThePlacesOwn() {
        assertThat(CommunityScore.rating(4.5, 0, 0)).isEqualTo(4.5);
        assertThat(CommunityScore.rating(null, 0, 0)).isEqualTo(CommunityScore.DEFAULT_RATING);
        assertThat(CommunityScore.average(0, 0)).isNull();
    }

    @Test
    void oneFiveStarVoteCannotBeatThirty() {
        double oneVote = CommunityScore.rating(null, 5, 1);
        double thirtyVotes = CommunityScore.rating(null, 150, 30);

        assertThat(oneVote).isCloseTo(4.0, within(0.01));
        assertThat(thirtyVotes).isCloseTo(4.83, within(0.01));
        // Thirty people at 4.5 on average still beat the single 5
        assertThat(CommunityScore.rating(null, 135, 30)).isGreaterThan(oneVote);
    }

    @Test
    void badVotesPullAPlaceDown() {
        assertThat(CommunityScore.rating(null, 20, 10)).isLessThan(CommunityScore.DEFAULT_RATING);
        assertThat(CommunityScore.average(20, 10)).isEqualTo(2.0);
    }

    @Test
    void anAreasFavouriteNeedsEnoughPeopleRatingItWell() {
        assertThat(CommunityScore.isFavorite(5, 1)).isFalse();
        assertThat(CommunityScore.isFavorite(23, 5)).isTrue();
        assertThat(CommunityScore.isFavorite(20, 5)).isFalse();
        assertThat(CommunityScore.isFavorite(0, 0)).isFalse();
    }

    @Test
    void thePlannerPrefersAPlaceUsersRatedWell() {
        Place unknown = place(1, "Lokanta A", PlaceCategory.RESTAURANT, true, 0, 0);
        unknown.setRating(null);
        Place liked = rated(place(2, "Lokanta B", PlaceCategory.RESTAURANT, true, 0, 0), 96, 20, 0);
        liked.setRating(null);

        PlaceScorer.ScoredPlace likedScore = score(liked, false);

        assertThat(likedScore.score()).isGreaterThan(score(unknown, false).score());
        assertThat(likedScore.reasons()).contains("Nomi kullanıcılarının puanı 4.8 (20 oy)");
    }

    @Test
    void aFewVotesAreNotShownAsARating() {
        Place place = rated(place(1, "Kafe", PlaceCategory.CAFE, true, 4.2, 0), 10, 2, 0);

        assertThat(score(place, false).reasons()).contains("Puanı 4.2").noneMatch(r -> r.contains("oy)"));
    }

    @Test
    void theUsersOwnSavedPlaceBeatsAnEquallyGoodOne() {
        Place saved = place(1, "Kafe A", PlaceCategory.CAFE, true, 4.2, 0);
        Place other = place(2, "Kafe B", PlaceCategory.CAFE, true, 4.2, 0);

        PlaceScorer.ScoredPlace mine = score(saved, true);

        assertThat(mine.score() - score(other, false).score()).isEqualTo(PlaceScorer.OWN_FAVORITE_BONUS);
        assertThat(mine.reasons()).contains("Kaydettiğin bir yer");
    }

    @Test
    void otherUsersSavesArePrivateAndChangeNothing() {
        Place savedByMany = rated(place(1, "Kafe A", PlaceCategory.CAFE, true, 4.2, 0), 0, 0, 12);
        Place other = place(2, "Kafe B", PlaceCategory.CAFE, true, 4.2, 0);

        PlaceScorer.ScoredPlace result = score(savedByMany, false);

        assertThat(result.score()).isEqualTo(score(other, false).score());
        assertThat(result.reasons()).noneMatch(r -> r.contains("kaydedilen"));
    }

    @Test
    void aSavedPlaceInTheRainStillLosesToAnIndoorOne() {
        WeatherContext rain = new WeatherContext(true, false, false, false, true);
        Place savedPark = place(1, "Park", PlaceCategory.PARK, false, 4.5, 0);
        Place museum = place(2, "Müze", PlaceCategory.MUSEUM, true, 4.5, 0);

        double park = scorer.score(new PlaceScorer.Candidate(savedPark, 300, 1200, 6, false, LocalTime.of(13, 0), rain,
                List.of(), null, null, true, null, true)).score();
        double indoor = scorer.score(new PlaceScorer.Candidate(museum, 300, 1200, 6, false, LocalTime.of(13, 0), rain,
                List.of(), null, null, true, null, false)).score();

        assertThat(indoor).isGreaterThan(park);
    }
}

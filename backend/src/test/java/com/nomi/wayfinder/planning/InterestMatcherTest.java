package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Sight;
import com.nomi.wayfinder.service.Interests;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

// Interests map to real data (tags, category, near the coast); labels from any client map to the same keys
class InterestMatcherTest {

    @Test
    void seaMeansTaggedOrNearTheCoastline() {
        Place beach = place(1, "Plaj", PlaceCategory.PARK, false, 4, 0, "sea", "nature");
        Place shoreCafe = place(2, "Kıyı Kafe", PlaceCategory.CAFE, true, 4, 0);
        ReflectionTestUtils.setField(shoreCafe, "nearSea", true);
        Place inland = place(3, "İç Kafe", PlaceCategory.CAFE, true, 4, 0);

        assertThat(InterestMatcher.matches(beach, "sea")).isTrue();
        assertThat(InterestMatcher.matches(shoreCafe, "sea")).isTrue();
        assertThat(InterestMatcher.matches(inland, "sea")).isFalse();
        // A café by the sea is not a "view" sight; a park on the shore is
        assertThat(InterestMatcher.matches(shoreCafe, "view")).isFalse();
        Place shorePark = place(4, "Sahil Parkı", PlaceCategory.PARK, false, 4, 0, "nature");
        ReflectionTestUtils.setField(shorePark, "nearSea", true);
        assertThat(InterestMatcher.matching(shorePark, List.of("sea", "nature", "view", "history")))
                .containsExactly("sea", "nature", "view");
    }

    @Test
    void natureBudgetMuseumArtAndLocalUseTagsAndCategories() {
        assertThat(InterestMatcher.matches(place(1, "Park", PlaceCategory.PARK, false, 4, 0), "nature")).isTrue();
        assertThat(InterestMatcher.matches(place(2, "Seyir Tepesi", PlaceCategory.ATTRACTION, false, 4, 0, "view"),
                "nature")).isTrue();
        assertThat(InterestMatcher.matches(place(3, "Çay Bahçesi", PlaceCategory.CAFE, false, 4, 0, "tea"),
                "budget")).isTrue();
        assertThat(InterestMatcher.matches(place(4, "Döner", PlaceCategory.RESTAURANT, true, 4, 0, "quick"),
                "budget")).isTrue();
        assertThat(InterestMatcher.matches(place(5, "Şık Restoran", PlaceCategory.RESTAURANT, true, 4, 0),
                "budget")).isFalse();
        assertThat(InterestMatcher.matches(place(6, "Müze", PlaceCategory.MUSEUM, true, 4, 0), "museum")).isTrue();
        assertThat(InterestMatcher.matches(place(7, "Tiyatro", PlaceCategory.CULTURE, true, 4, 0), "art")).isTrue();
        assertThat(InterestMatcher.matches(place(8, "Pideci", PlaceCategory.RESTAURANT, true, 4, 0, "local"),
                "local")).isTrue();
        assertThat(InterestMatcher.tagsCsv(List.of("budget", "sea"))).isEqualTo("bakery,budget,quick,sea,tea");
        assertThat(InterestMatcher.wantsSea(List.of("nature"))).isFalse();
    }

    @Test
    void labelsInEitherLanguageBecomeKeys() {
        assertThat(Interests.normalize(List.of("Deniz", "doğa", "Uygun fiyat", "sea", " TARİH ", "street art",
                "Deniz ürünleri", "music")))
                .containsExactly("sea", "nature", "budget", "history", "street-art", "seafood", "music");
    }

    @Test
    void popularRoutesKeepOneOfTwoEntriesOfTheSameSight() {
        List<Sight> kept = PopularRouteBuilder.dedupe(List.of(
                sight(1, "Taksim Gezi Parkı", 12, 41.0370, 28.9870),
                sight(2, "Gezi Parkı", 14, 41.0375, 28.9868),
                sight(3, "Ayasofya", 22, 41.0086, 28.9802),
                sight(4, "Ayasofya Hürrem Sultan Hamamı", 12, 41.0077, 28.9794)));

        assertThat(kept).extracting(s -> s.place().getName())
                .containsExactly("Ayasofya", "Gezi Parkı", "Ayasofya Hürrem Sultan Hamamı");
    }

    private static Sight sight(long id, String name, double score, double lat, double lon) {
        Place place = place(id, name, PlaceCategory.ATTRACTION, false, 0, 0);
        place.setCoordinates(lat, lon);
        return new Sight(place, score);
    }
}

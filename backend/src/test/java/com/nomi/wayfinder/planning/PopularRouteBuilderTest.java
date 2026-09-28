package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Area;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Cluster;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Itinerary;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Sight;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

class PopularRouteBuilderTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);

    @Test
    void groupsAreCentredOnTheMostPopularSightAndStayOnOneSideOfTheBosphorus() {
        List<Sight> sights = List.of(
                sight(1, "Dolmabahçe Sarayı", 20, 41.0391, 29.0005),
                sight(2, "Deniz Müzesi", 9, 41.0417, 29.0055),
                sight(3, "Beşiktaş Meydanı", 5, 41.0425, 29.0070),
                // Üsküdar, on the Asian side
                sight(4, "Kız Kulesi", 18, 41.0230, 29.0080),
                sight(5, "Mihrimah Sultan Camii", 10, 41.0270, 29.0150),
                sight(6, "Şemsi Paşa Camii", 8, 41.0265, 29.0120),
                // Same place twice in OSM (node + way): merged
                sight(7, "Dolmabahçe Sarayı", 19, 41.0392, 29.0001),
                // Only ~800 m from Kız Kulesi, but across the Bosphorus: never in its walking group
                sight(8, "Molla Çelebi Camii", 6, 41.0280, 29.0010));

        List<Cluster> clusters = PopularRouteBuilder.clusters(sights);

        // Ranked by the summed popularity of their sights: 18 + 10 + 8 before 20 + 9 + 5
        assertThat(clusters).extracting(c -> c.centre().place().getName()).containsExactly("Kız Kulesi", "Dolmabahçe Sarayı");
        assertThat(clusters.get(0).sights()).extracting(s -> s.place().getId()).containsExactly(4L, 5L, 6L);
        assertThat(clusters.get(1).sights()).extracting(s -> s.place().getId()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void groupsNeedThreeSights() {
        assertThat(PopularRouteBuilder.clusters(List.of(
                sight(1, "Anıtkabir", 20, 39.9250, 32.8370),
                sight(2, "Kocatepe Camii", 12, 39.9160, 32.8600)))).isEmpty();
    }

    @Test
    void mealsComeAsTheDayAdvances() {
        List<Sight> order = List.of(
                sight(1, "A", 20, 41.0086, 28.9802, PlaceCategory.MUSEUM),
                sight(2, "B", 19, 41.0084, 28.9779, PlaceCategory.MUSEUM),
                sight(3, "C", 18, 41.0054, 28.9768, PlaceCategory.MUSEUM),
                sight(4, "D", 17, 41.0115, 28.9834, PlaceCategory.MUSEUM));

        Itinerary day = PopularRouteBuilder.simulate(order, MONDAY).orElseThrow();

        // 4 museums of 75 min from 09:30: lunch once it is past noon, a dessert break late in the afternoon
        assertThat(day.items()).extracting(PopularRouteBuilder.Item::type).containsExactly(
                StopType.SIGHTSEEING, StopType.SIGHTSEEING, StopType.LUNCH, StopType.SIGHTSEEING,
                StopType.SIGHTSEEING, StopType.DESSERT);
        assertThat(day.items().getFirst().arrival()).isEqualTo(LocalTime.of(9, 30));
        assertThat(day.items().get(2).arrival()).isAfterOrEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void theShortestOpenOrderWins() {
        // A line west to east: visiting 1, 2, 3 in order is shortest
        Sight a = sight(1, "A", 20, 41.0, 28.970);
        Sight b = sight(2, "B", 10, 41.0, 28.975);
        Sight c = sight(3, "C", 15, 41.0, 28.980);
        Itinerary day = PopularRouteBuilder.itinerary(new Cluster(a, List.of(a, c, b)), MONDAY).orElseThrow();
        List<Long> ids = day.sights().stream().map(s -> s.place().getId()).toList();
        assertThat(ids).isIn(List.of(1L, 2L, 3L), List.of(3L, 2L, 1L));
    }

    @Test
    void popularityFallsBackToOurOwnDataButNeverToNothing() {
        assertThat(PopularRouteBuilder.fallbackScore(false, null, false)).isZero();
        assertThat(PopularRouteBuilder.fallbackScore(true, 4.5, true)).isEqualTo(3 + 1.5 + 1);
        assertThat(PopularRouteBuilder.fallbackScore(false, 2.0, false)).isZero();
    }

    @Test
    void titlesUseRealAreaNamesThePlacesCarry() {
        List<Sight> galata = List.of(sight(1, "Galata Kulesi", 19, 41.0256, 28.9741),
                sight(2, "Karaköy Güllüoğlu", 8, 41.0232, 28.9770), sight(3, "Kamondo Merdivenleri", 7, 41.0240, 28.9737));
        List<Area> areas = List.of(new Area("Galata", "quarter", 41.0250, 28.9740),
                new Area("Karaköy", "quarter", 41.0230, 28.9770), new Area("Merkez", "suburb", 41.0245, 28.9750));

        assertThat(PopularRouteBuilder.areaTitle(galata, areas, List.of("Galata Kahve", "Karaköy Lokantası"), Set.of()))
                .isEqualTo("Galata–Karaköy");
        // Already used by another route of the list
        assertThat(PopularRouteBuilder.areaTitle(galata, areas, List.of(), Set.of("Galata"))).isEqualTo("Karaköy");
        // An area whose name the places carry but that is far from the group does not name it
        List<Area> withFar = List.of(new Area("Balat", "quarter", 41.0300, 28.9480),
                new Area("Galata", "quarter", 41.0250, 28.9740));
        assertThat(PopularRouteBuilder.areaTitle(galata, withFar, List.of("Balat Kahvesi", "Balat Evi"), Set.of()))
                .isEqualTo("Galata");
        // No area near: the caller falls back to the district
        assertThat(PopularRouteBuilder.areaTitle(galata, List.of(), List.of(), Set.of())).isNull();
    }

    private static Sight sight(long id, String name, double score, double lat, double lon) {
        return sight(id, name, score, lat, lon, PlaceCategory.ATTRACTION);
    }

    private static Sight sight(long id, String name, double score, double lat, double lon, PlaceCategory category) {
        Place place = place(id, name, category, false, 0, 0);
        place.setRating(null);
        place.setCoordinates(lat, lon);
        ReflectionTestUtils.setField(place, "popularity", score);
        return new Sight(place, score);
    }
}

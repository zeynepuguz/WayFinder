package com.nomi.wayfinder.service;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.UserPreferences;
import com.nomi.wayfinder.planning.PlaceScorer;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import com.nomi.wayfinder.service.RecommendationService.TieredRecommendations;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static com.nomi.wayfinder.TestPlaces.openEveryDay;
import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// "Daha uygun ama sana yakın değil": farther places only when they fit clearly better, explained from real data
class TieredRecommendationTest {

    // Monday 15:00 in Istanbul
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneId.of("Europe/Istanbul"));

    private final List<PlaceDistance> nearby = new ArrayList<>();
    private final List<PlaceDistance> ring = new ArrayList<>();
    private final Map<Long, Place> places = new HashMap<>();
    private final UserPreferences preferences = new UserPreferences(1L);
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        PlaceRepository repository = mock(PlaceRepository.class);
        WeatherService weatherService = mock(WeatherService.class);
        UserService userService = mock(UserService.class);

        when(repository.findCandidates(anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(), anyInt()))
                .thenAnswer(inv -> nearby);
        when(repository.findCandidatesInRing(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyCollection(),
                any(), any(), anyInt()))
                .thenAnswer(inv -> ring);
        when(repository.findByIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(places::get).toList();
        });
        when(weatherService.getForecast(anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());
        when(userService.getPreferences(1L)).thenReturn(preferences);

        PlaceMapper mapper = new PlaceMapper(CLOCK);
        service = new RecommendationService(repository, new PlaceService(repository, mapper), new PlaceScorer(),
                mapper, weatherService, userService, CLOCK);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void fartherPlaceIsShownOnlyWhenClearlyBetterWithDistanceLineFirst() {
        add(nearby, place(1, "Yakın Kafe", PlaceCategory.CAFE, true, 4.0, 100), 300);
        add(ring, place(2, "Çok İyi Kafe", PlaceCategory.CAFE, true, 4.7, 100), 1800);
        // Only a little better (4.2 vs 4.0): not worth the walk
        add(ring, place(3, "Biraz İyi Kafe", PlaceCategory.CAFE, true, 4.2, 100), 2000);

        TieredRecommendations result = service.recommendTiered(40.99, 29.02, StopType.COFFEE, null, 5, 3);

        assertThat(result.nearby()).extracting(r -> r.place().getName()).containsExactly("Yakın Kafe");
        assertThat(result.nearby().getFirst().whyBetter()).isNull();

        assertThat(result.farther()).extracting(r -> r.place().getName()).containsExactly("Çok İyi Kafe");
        Recommendation far = result.farther().getFirst();
        // 1800 m * 1.3 detour / 75 m per min = 31.2 -> 32 min
        assertThat(far.reasons().getFirst()).isEqualTo("1,8 km uzakta (yürüyerek ~32 dk)");
        assertThat(far.reasons()).noneMatch(r -> r.startsWith("Başlangıç noktana"));
        assertThat(far.whyBetter()).isEqualTo("Puanı daha yüksek (4.7)");
        assertThat(far.place().getDistanceMeters()).isEqualTo(1800.0);
    }

    @Test
    void whyBetterListsOnlyTheRealFactorsInBothLanguages() {
        preferences.setInterests(List.of("books"));
        // Same (unknown) rating; the farther one matches an interest and its hours are known
        Place near = place(1, "Yakın", PlaceCategory.CAFE, true, 4.0, 100);
        near.setRating(null);
        Place far = openEveryDay(place(2, "Kitap Kafe", PlaceCategory.CAFE, true, 4.0, 100, "books"), "08:00", "23:00");
        far.setRating(null);
        add(nearby, near, 200);
        add(ring, far, 2500);

        Recommendation tr = service.recommendTiered(40.99, 29.02, StopType.COFFEE, 1L, 5, 3).farther().getFirst();
        // Turkish capitalization: "ilgi" -> "İlgi"
        assertThat(tr.whyBetter()).isEqualTo("İlgi alanına uygun ve açık olduğu biliniyor");
        assertThat(tr.whyBetter()).doesNotContain("Puan");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        Recommendation en = service.recommendTiered(40.99, 29.02, StopType.COFFEE, 1L, 5, 3).farther().getFirst();
        assertThat(en.reasons().getFirst()).isEqualTo("2.5 km away (~44 min walk)");
        assertThat(en.whyBetter()).isEqualTo("Matches your interests and known to be open");
    }

    @Test
    void whenNothingIsNearbyGoodFartherPlacesAreShown() {
        add(ring, place(2, "Uzak Kafe", PlaceCategory.CAFE, true, 4.3, 100), 3000);
        add(ring, place(3, "Kötü Kafe", PlaceCategory.CAFE, true, 2.0, 100), 2000);

        TieredRecommendations result = service.recommendTiered(40.99, 29.02, StopType.COFFEE, null, 5, 3);

        assertThat(result.nearby()).isEmpty();
        assertThat(result.farther()).extracting(r -> r.place().getName()).containsExactly("Uzak Kafe");
        assertThat(result.farther().getFirst().whyBetter()).isEqualTo("Yakında açık ve uygun bir seçenek yok");
    }

    @Test
    void closedFartherPlacesAreSkippedAndPlainRecommendStaysNearbyOnly() {
        add(nearby, place(1, "Yakın Kafe", PlaceCategory.CAFE, true, 3.5, 100), 300);
        add(ring, openEveryDay(place(2, "Gece Kafesi", PlaceCategory.CAFE, true, 4.9, 100), "20:00", "23:00"), 1500);

        assertThat(service.recommendTiered(40.99, 29.02, StopType.COFFEE, null, 5, 3).farther()).isEmpty();
        assertThat(service.recommend(40.99, 29.02, StopType.COFFEE, null, 5))
                .extracting(Recommendation::whyBetter).containsExactly((String) null);
    }

    private void add(List<PlaceDistance> list, Place place, double distance) {
        places.put(place.getId(), place);
        list.add(new Candidate(place.getId(), distance));
    }

    private record Candidate(Long id, double distance) implements PlaceDistance {

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public Double getDistanceMeters() {
            return distance;
        }
    }
}

package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static com.nomi.wayfinder.TestPlaces.openEveryDay;
import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Route shape and preferences with real geometry: the fake repository measures distances between the places'
 * coordinates like PostGIS would (candidate queries, interest queries), so look-ahead, ordering and leg caps work
 * as in the app.
 */
class RouteShapeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final double START_LAT = 40.99;
    private static final double START_LON = 29.02;

    private final Map<Long, Place> places = new LinkedHashMap<>();
    private RoutePlanner planner;

    @BeforeEach
    void setUp() {
        PlaceRepository repository = mock(PlaceRepository.class);
        WeatherService weather = mock(WeatherService.class);
        when(weather.getForecast(anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());
        planner = new RoutePlanner(repository, new PlaceScorer(), weather);

        when(repository.findCandidates(anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(), anyInt()))
                .thenAnswer(inv -> near(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4), null, false));
        when(repository.findInterestCandidates(anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(), any(),
                anyBoolean(), anyInt()))
                .thenAnswer(inv -> near(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4), inv.getArgument(5), inv.getArgument(6)));
        when(repository.findByIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(places::get).toList();
        });
    }

    // ---------- route shape ----------

    @Test
    void flexibleStopsAreVisitedInTheOrderWithTheLeastWalking() {
        // Only one place per stop: sight 1 km east, café at the start, dessert 1.1 km east. Visiting them in slot
        // order walks east, back west and east again
        add(at(place(1, "Doğu Müzesi", PlaceCategory.MUSEUM, true, 4.5, 0), 0, 1000));
        add(at(place(2, "Başlangıç Kafe", PlaceCategory.CAFE, true, 4.5, 0), 0, -50));
        add(at(place(3, "Doğu Pastanesi", PlaceCategory.DESSERT, true, 4.5, 0), 0, 1100));

        PlanResult result = planner.plan(request(List.of(
                PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0)),
                PlanningSlot.at(StopType.COFFEE, LocalTime.of(15, 0)),
                PlanningSlot.at(StopType.DESSERT, LocalTime.of(18, 30))), WalkingTolerance.MEDIUM, List.of(), null));

        assertThat(result.stops()).extracting(s -> s.place().getName())
                .containsExactly("Başlangıç Kafe", "Doğu Müzesi", "Doğu Pastanesi");
        int walked = result.stops().stream().mapToInt(PlannedStop::distanceFromPreviousMeters).sum();
        assertThat(walked).isLessThan(1400);
        // Times still go forward and the leg reasons describe the new legs
        for (int i = 1; i < result.stops().size(); i++) {
            assertThat(result.stops().get(i).start()).isAfterOrEqualTo(result.stops().get(i - 1).end());
        }
        assertThat(result.stops().get(1).reasons().getFirst()).startsWith("Önceki durağa 1");
    }

    @Test
    void mealsKeepTheirPlaceInTheDay() {
        add(at(place(1, "Doğu Müzesi", PlaceCategory.MUSEUM, true, 4.5, 0), 0, 900));
        add(at(place(2, "Batı Kafe", PlaceCategory.CAFE, true, 4.5, 0), 0, -100));
        add(at(place(3, "Kahvaltı Evi", PlaceCategory.BREAKFAST, true, 4.5, 0), 0, 100));
        add(at(place(4, "Akşam Lokantası", PlaceCategory.RESTAURANT, true, 4.5, 0), 0, 950));

        PlanResult result = planner.plan(request(List.of(
                PlanningSlot.at(StopType.BREAKFAST, LocalTime.of(9, 30)),
                PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0)),
                PlanningSlot.at(StopType.COFFEE, LocalTime.of(15, 0)),
                PlanningSlot.at(StopType.DINNER, LocalTime.of(20, 0))), WalkingTolerance.MEDIUM, List.of(), null));

        assertThat(result.stops()).extracting(PlannedStop::type)
                .containsExactly(StopType.BREAKFAST, StopType.COFFEE, StopType.SIGHTSEEING, StopType.DINNER);
        assertThat(result.stops().getFirst().start()).isBefore(LocalTime.of(9, 30));
        assertThat(result.stops().getLast().start()).isBetween(LocalTime.of(19, 30), LocalTime.of(20, 30));
    }

    @Test
    void lowWalkingKeepsLegsShort() {
        add(at(place(1, "Uzak Kafe", PlaceCategory.CAFE, true, 4.9, 0), 0, 1000));

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.COFFEE, LocalTime.of(15, 0))),
                WalkingTolerance.LOW, List.of(), null));

        // 1 km is more than "az yürüyelim" allows even when looking a bit further (600 m * 1.5)
        assertThat(result.stops()).isEmpty();
        assertThat(result.notes()).anyMatch(n -> n.contains("Kahve için bu saatte açık ve uygun bir mekan bulunamadı"));
    }

    @Test
    void theSamePlaceMappedTwiceIsNotTwoStops() {
        add(at(place(1, "Kelebek Cafe", PlaceCategory.CAFE, true, 4.5, 0), 100, 0));
        add(at(place(2, "Kelebek Cafe ve Restaurant", PlaceCategory.CAFE, true, 4.5, 0), 103, 2));
        add(at(place(3, "Köşe Kahvecisi", PlaceCategory.CAFE, true, 4.0, 0), 400, 0));

        PlanResult result = planner.plan(request(List.of(
                PlanningSlot.at(StopType.COFFEE, LocalTime.of(10, 0)),
                PlanningSlot.at(StopType.COFFEE, null)), WalkingTolerance.MEDIUM, List.of(), null));

        assertThat(result.stops()).extracting(s -> s.place().getName())
                .containsExactly("Kelebek Cafe", "Köşe Kahvecisi");
    }

    // ---------- breakfast ----------

    @Test
    void breakfastFallsBackToAnOpenCafeWhenNoBreakfastPlaceIsOpen() {
        add(at(openEveryDay(place(1, "Öğlen Kahvaltıcısı", PlaceCategory.BREAKFAST, true, 4.8, 0), "12:00", "20:00"),
                100, 0));
        add(at(openEveryDay(place(2, "Sabah Börekçisi", PlaceCategory.CAFE, true, 4.2, 0, "bakery"), "07:00", "20:00"),
                200, 0));

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.BREAKFAST, LocalTime.of(9, 30))),
                WalkingTolerance.MEDIUM, List.of(), null));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Sabah Börekçisi");
        assertThat(result.stops().getFirst().type()).isEqualTo(StopType.BREAKFAST);
        assertThat(result.notes()).anyMatch(n -> n.contains("kahvaltı için açık olan Sabah Börekçisi"));
    }

    // ---------- interests ----------

    @Test
    void seaAndNatureInterestsBeatASmallDistanceAdvantage() {
        add(at(place(1, "Yakın Müze", PlaceCategory.MUSEUM, true, 4.5, 0), 100, 0));
        add(at(nearSea(place(2, "Sahil Parkı", PlaceCategory.PARK, false, 4.5, 0, "nature")), 500, 0));

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                WalkingTolerance.MEDIUM, List.of("sea", "nature", "budget"), 950));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Sahil Parkı");
        assertThat(result.stops().getFirst().reasons()).anyMatch(r -> r.contains("İlgi alanına uygun: deniz, doğa"));
        assertThat(result.notes()).contains("Deniz ve doğa tercihine göre seçilen duraklar: Sahil Parkı.");
        assertThat(result.notes()).contains("Bu bölgede uygun fiyat tercihine uygun mekan bulunamadı.");
    }

    @Test
    void withoutInterestsTheCloserPlaceWins() {
        add(at(place(1, "Yakın Müze", PlaceCategory.MUSEUM, true, 4.5, 0), 100, 0));
        add(at(nearSea(place(2, "Sahil Parkı", PlaceCategory.PARK, false, 4.5, 0, "nature")), 500, 0));

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                WalkingTolerance.MEDIUM, List.of(), null));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Yakın Müze");
    }

    @Test
    void anUnmatchedInterestIsSaid() {
        add(at(place(1, "Yakın Müze", PlaceCategory.MUSEUM, true, 4.5, 0), 100, 0));

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                WalkingTolerance.MEDIUM, List.of("sea"), null));

        assertThat(result.stops()).hasSize(1);
        assertThat(result.notes()).contains("Bu bölgede deniz kenarı mekan bulunamadı.");
    }

    @Test
    void sightseeingGoesALittleFurtherForAMatchingPlace() {
        add(at(place(1, "Yakın Park", PlaceCategory.PARK, true, 4.5, 0), 200, 0));
        add(at(place(2, "Tarihi Hamam", PlaceCategory.ATTRACTION, true, 4.5, 0, "history"), 800, 0));

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                WalkingTolerance.LOW, List.of("history"), null));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Tarihi Hamam");
        assertThat(result.notes()).anyMatch(n -> n.startsWith("Tarih tercihin için biraz daha uzaktaki Tarihi Hamam"));
    }

    @Test
    void budgetInterestPrefersCheapPlacesAndCafesForMeals() {
        Place fancy = at(place(1, "Şık Restoran", PlaceCategory.RESTAURANT, true, 4.5, 0), 100, 0);
        fancy.setEstimatedCost(null);
        Place municipal = at(place(2, "Belediye Sosyal Tesisleri", PlaceCategory.RESTAURANT, true, 4.5, 0, "budget"),
                400, 0);
        municipal.setEstimatedCost(null);
        add(fancy);
        add(municipal);

        PlanResult lunch = planner.plan(request(List.of(PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0))),
                WalkingTolerance.MEDIUM, List.of("budget"), 950));
        assertThat(lunch.stops()).extracting(s -> s.place().getName()).containsExactly("Belediye Sosyal Tesisleri");

        places.remove(2L);
        Place cafe = at(place(3, "Mahalle Kafesi", PlaceCategory.CAFE, true, 4.5, 0), 300, 0);
        cafe.setEstimatedCost(null);
        add(cafe);
        PlanResult cafeLunch = planner.plan(request(List.of(PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0))),
                WalkingTolerance.MEDIUM, List.of("budget"), 950));
        assertThat(cafeLunch.stops()).extracting(s -> s.place().getName()).containsExactly("Mahalle Kafesi");
        assertThat(cafeLunch.stops().getFirst().reasons()).contains("Uygun fiyat için restoran yerine kafe");
    }

    @Test
    void aKnownPriceWithinBudgetRanksAboveAnUnknownOne() {
        Place unknown = at(place(1, "Fiyatı Bilinmeyen", PlaceCategory.RESTAURANT, true, 4.5, 0), 100, 0);
        unknown.setEstimatedCost(null);
        add(unknown);
        add(at(place(2, "Fiyatı Belli", PlaceCategory.RESTAURANT, true, 4.5, 250), 350, 0));

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0))),
                WalkingTolerance.MEDIUM, List.of(), 950));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Fiyatı Belli");
    }

    // ---------- helpers ----------

    private PlanningRequest request(List<PlanningSlot> slots, WalkingTolerance tolerance, List<String> interests,
                                    Integer budget) {
        return new PlanningRequest(START_LAT, START_LON, DAY, LocalTime.of(9, 0), LocalTime.of(22, 0), 1, budget,
                tolerance, interests, slots, Set.of(), false);
    }

    private void add(Place place) {
        places.put(place.getId(), place);
    }

    // Meters north / east of the start
    private static Place at(Place place, double north, double east) {
        double lat = START_LAT + north / 111_320.0;
        double lon = START_LON + east / (111_320.0 * Math.cos(Math.toRadians(START_LAT)));
        place.setCoordinates(lat, lon);
        return place;
    }

    private static Place nearSea(Place place) {
        ReflectionTestUtils.setField(place, "nearSea", true);
        return place;
    }

    private List<PlaceDistance> near(double lat, double lon, double radius, Collection<String> categories, String tag,
                                     String interestTags, boolean nearSea) {
        Set<String> wanted = interestTags == null || interestTags.isEmpty() ? Set.of()
                : Set.of(interestTags.split(","));
        List<PlaceDistance> result = new ArrayList<>();
        for (Place place : places.values()) {
            double distance = PopularRouteBuilder.meters(lat, lon, place.getLatitude(), place.getLongitude());
            boolean kind = categories.contains(place.getCategory().name()) || (tag != null && place.hasTag(tag));
            boolean interest = interestTags == null
                    || place.getTags().stream().anyMatch(wanted::contains) || (nearSea && place.isNearSea());
            if (distance <= radius && kind && interest) {
                result.add(new Found(place.getId(), distance));
            }
        }
        result.sort(Comparator.comparing(PlaceDistance::getDistanceMeters));
        return result;
    }

    private record Found(Long id, double distance) implements PlaceDistance {

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

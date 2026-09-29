package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.weather.HourlyWeather;
import com.nomi.wayfinder.weather.WeatherForecast;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static com.nomi.wayfinder.TestPlaces.openEveryDay;
import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutePlannerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    private PlaceRepository repository;
    private WeatherService weatherService;
    private RoutePlanner planner;

    // candidates per category, with fake PostGIS distances
    private final Map<PlaceCategory, List<Candidate>> candidates = new EnumMap<>(PlaceCategory.class);
    private final Map<Long, Place> places = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(PlaceRepository.class);
        weatherService = mock(WeatherService.class);
        planner = new RoutePlanner(repository, new PlaceScorer(), weatherService);

        when(weatherService.getForecast(anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());

        when(repository.findCandidates(anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(), anyInt()))
                .thenAnswer(inv -> {
                    double radius = inv.getArgument(2);
                    Collection<String> categories = inv.getArgument(3);
                    List<PlaceDistance> result = new ArrayList<>();
                    categories.forEach(c -> candidates.getOrDefault(PlaceCategory.valueOf(c), List.of()).stream()
                            .filter(cand -> cand.distance <= radius)
                            .forEach(cand -> result.add(cand)));
                    result.sort(Comparator.comparing(PlaceDistance::getDistanceMeters));
                    return result;
                });
        when(repository.findByIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(places::get).toList();
        });
    }

    @Test
    void skipsPlacesThatAreClosedAtArrival() {
        add(openEveryDay(place(1, "Closed Cafe", PlaceCategory.CAFE, true, 4.9, 100), "18:00", "23:00"), 100);
        add(openEveryDay(place(2, "Open Cafe", PlaceCategory.CAFE, true, 4.0, 100), "08:00", "23:00"), 400);

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.COFFEE, LocalTime.of(10, 0))),
                null, false));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Open Cafe");
    }

    @Test
    void prefersIndoorSightseeingWhenItRains() {
        add(place(1, "Sahil", PlaceCategory.PARK, false, 4.8, 0), 200);
        add(place(2, "Müze", PlaceCategory.MUSEUM, true, 4.3, 0), 400);

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                null, true));

        assertThat(result.stops().getFirst().place().getName()).isEqualTo("Müze");
    }

    @Test
    void warnsWhenOnlyAnOutdoorPlaceIsLeftInTheRain() {
        add(place(1, "Sahil", PlaceCategory.PARK, false, 4.8, 0), 200);

        PlanResult rainy = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                null, true));
        PlanResult dry = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                null, false));

        assertThat(rainy.stops()).extracting(s -> s.place().getName()).containsExactly("Sahil");
        assertThat(rainy.notes()).anyMatch(n -> n.contains("Sahil açık alan ve o saatte yağış bekleniyor"));
        assertThat(dry.notes()).noneMatch(n -> n.contains("yağış bekleniyor"));
    }

    @Test
    void usesTheForecastAtTheTimeOfEachStop() {
        add(place(1, "Sahil", PlaceCategory.PARK, false, 4.8, 0, "sea"), 200);
        add(place(2, "Müze", PlaceCategory.MUSEUM, true, 4.3, 0), 400);
        when(weatherService.getForecast(anyDouble(), anyDouble(), any()))
                .thenReturn(Optional.of(forecastRainingUntil(15)));
        when(weatherService.advice(any(WeatherForecast.class), any(), any())).thenReturn("advice");

        PlanResult morning = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                null, false));
        PlanResult evening = planner.plan(new PlanningRequest(40.99, 29.02, DAY, LocalTime.of(18, 0),
                LocalTime.of(22, 0), 1, null, WalkingTolerance.MEDIUM, List.of(),
                List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(18, 30))), Set.of(), false));

        assertThat(morning.stops().getFirst().place().getName()).isEqualTo("Müze");
        assertThat(evening.stops().getFirst().place().getName()).isEqualTo("Sahil");
    }

    @Test
    void doesNotUseTheSamePlaceTwice() {
        add(place(1, "Cafe A", PlaceCategory.CAFE, true, 4.5, 100), 100);
        add(place(2, "Cafe B", PlaceCategory.CAFE, true, 4.0, 100), 300);

        PlanResult result = planner.plan(request(List.of(
                PlanningSlot.at(StopType.COFFEE, LocalTime.of(10, 0)),
                PlanningSlot.next(StopType.COFFEE, null)), null, false));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Cafe A", "Cafe B");
    }

    @Test
    void choosesAffordablePlaceAndWarnsWhenBudgetIsExceeded() {
        add(place(1, "Pahalı", PlaceCategory.RESTAURANT, true, 4.9, 1000), 100);
        add(place(2, "Uygun", PlaceCategory.RESTAURANT, true, 4.0, 200), 300);

        PlanResult affordable = planner.plan(request(List.of(PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0))),
                400, false));
        assertThat(affordable.stops().getFirst().place().getName()).isEqualTo("Uygun");

        PlanResult tooSmall = planner.plan(request(List.of(PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0))),
                50, false));
        assertThat(tooSmall.stops()).hasSize(1);
        assertThat(tooSmall.notes()).anyMatch(n -> n.contains("bütçeyi"));
    }

    @Test
    void keepsStopsInTimeOrderAndInsideTheDay() {
        add(place(1, "Kahvaltıcı", PlaceCategory.BREAKFAST, true, 4.5, 100), 100);
        add(place(2, "Restoran", PlaceCategory.RESTAURANT, true, 4.5, 100), 200);

        PlanResult result = planner.plan(new PlanningRequest(40.99, 29.02, DAY, LocalTime.of(9, 0),
                LocalTime.of(12, 0), 1, null, WalkingTolerance.MEDIUM, List.of(),
                List.of(PlanningSlot.of(StopType.BREAKFAST), PlanningSlot.of(StopType.DINNER)), Set.of(), false));

        assertThat(result.stops()).extracting(PlannedStop::type).containsExactly(StopType.BREAKFAST);
        assertThat(result.notes()).anyMatch(n -> n.contains("Akşam yemeği gün sonuna sığmadığı"));
        assertThat(result.stops().getFirst().start()).isAfterOrEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void inTheRainAnIndoorSightBeatsAnOutdoorOneThatMatchesTheInterests() {
        add(place(1, "Park", PlaceCategory.PARK, false, 4.5, 0, "nature"), 100);
        add(place(2, "Kültür Merkezi", PlaceCategory.CULTURE, true, 4.0, 0), 600);

        PlanResult rainy = planner.plan(new PlanningRequest(40.99, 29.02, DAY, LocalTime.of(9, 0),
                LocalTime.of(22, 0), 1, null, WalkingTolerance.MEDIUM, List.of("nature"),
                List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))), Set.of(), true));
        PlanResult dry = planner.plan(new PlanningRequest(40.99, 29.02, DAY, LocalTime.of(9, 0),
                LocalTime.of(22, 0), 1, null, WalkingTolerance.MEDIUM, List.of("nature"),
                List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))), Set.of(), false));

        assertThat(rainy.stops().getFirst().place().getName()).isEqualTo("Kültür Merkezi");
        // Not "no nature place in this area": there is one, the rain ruled it out
        assertThat(rainy.notes()).anyMatch(n -> n.startsWith("Yağış beklendiği için doğa tercihine"))
                .noneMatch(n -> n.contains("doğa tercihine uygun mekan bulunamadı"));
        assertThat(dry.stops().getFirst().place().getName()).isEqualTo("Park");
    }

    @Test
    void aKiraathaneIsNeverTheBreakfastStop() {
        add(place(1, "Joker Kıraathanesi", PlaceCategory.CAFE, true, 4.5, 50), 100);
        add(place(2, "Köşe Börekçisi", PlaceCategory.CAFE, true, 4.0, 80, "bakery"), 400);

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.BREAKFAST, LocalTime.of(9, 30))),
                null, false));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Köşe Börekçisi");
    }

    @Test
    void aSightAfterLunchDoesNotWaitHoursForItsTemplateTime() {
        add(place(1, "Restoran", PlaceCategory.RESTAURANT, true, 4.5, 100), 100);
        add(place(2, "Park", PlaceCategory.PARK, false, 4.5, 0), 200);

        PlanResult result = planner.plan(request(List.of(
                PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0)),
                PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(16, 30))), null, false));

        PlannedStop lunch = result.stops().get(0);
        PlannedStop park = result.stops().get(1);
        assertThat(lunch.start()).isEqualTo(LocalTime.of(12, 30));
        // Was 15:00 (16:30 minus 90 min of flexibility): a 1.5 h hole after lunch
        assertThat(park.start()).isBefore(lunch.end().plusMinutes(RoutePlanner.FLEXIBLE_MAX_WAIT_MINUTES + 6));
    }

    @Test
    void mealsStillWaitForTheirTime() {
        add(place(1, "Park", PlaceCategory.PARK, false, 4.5, 0), 100);
        add(place(2, "Restoran", PlaceCategory.RESTAURANT, true, 4.5, 100), 200);

        PlanResult result = planner.plan(request(List.of(
                PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(9, 0)),
                PlanningSlot.at(StopType.LUNCH, LocalTime.of(13, 0))), null, false));

        assertThat(result.stops().get(1).start()).isEqualTo(LocalTime.of(12, 30));
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void writesNotesAndReasonsInEnglishForEnglishRequests() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        add(place(1, "Sahil", PlaceCategory.PARK, false, 4.8, 0), 200);
        add(place(2, "Kahvaltıcı", PlaceCategory.BREAKFAST, true, 4.5, 100), 100);
        add(place(3, "Restoran", PlaceCategory.RESTAURANT, true, 4.5, 0), 200);

        PlanResult rainy = planner.plan(request(List.of(PlanningSlot.at(StopType.SIGHTSEEING, LocalTime.of(11, 0))),
                null, true));
        assertThat(rainy.notes()).contains(
                "Sahil is outdoors and rain is expected at that time; I could not find a suitable indoor sightseeing place nearby.");
        assertThat(rainy.weatherAdvice()).isEqualTo("I prioritized indoor places because of the rain.");
        assertThat(rainy.stops().getFirst().reasons())
                .contains("200 m from your starting point (~4 min walk)", "Rated 4.8", "Free");

        PlanResult late = planner.plan(new PlanningRequest(40.99, 29.02, DAY, LocalTime.of(9, 0),
                LocalTime.of(12, 0), 1, 50, WalkingTolerance.MEDIUM, List.of(),
                List.of(PlanningSlot.of(StopType.BREAKFAST), PlanningSlot.of(StopType.DINNER)), Set.of(), false));
        assertThat(late.notes()).contains(
                "Weather information was not available; the plan was made without taking the weather into account.",
                "Dinner was not added because it did not fit before the end of the day.",
                "Estimated spending is about 50 TL over the budget.");
        assertThat(late.stops().getFirst().reasons()).contains("~100 TL per person");
    }

    @Test
    void staysTurkishWithoutAnEnglishRequest() {
        add(place(2, "Kahvaltıcı", PlaceCategory.BREAKFAST, true, 4.5, 100), 100);

        PlanResult result = planner.plan(request(List.of(PlanningSlot.of(StopType.BREAKFAST)), null, false));

        assertThat(result.notes()).containsExactly(
                "Hava durumu bilgisi alınamadı; plan hava durumu dikkate alınmadan oluşturuldu.");
        assertThat(result.stops().getFirst().reasons())
                .contains("Başlangıç noktana 100 m (~2 dk yürüme)", "Puanı 4.5", "Kişi başı ~100 TL");
    }

    // ---------- helpers ----------


    @Test
    void unknownPricesAreLeftOutOfTheTotalWithANote() {
        Place unknown = place(1, "OSM Cafe", PlaceCategory.CAFE, true, 4.5, 0);
        unknown.setEstimatedCost(null);
        add(unknown, 100);

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.COFFEE, LocalTime.of(10, 0))),
                500, false));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("OSM Cafe");
        assertThat(result.stops().getFirst().reasons()).contains("Fiyat bilgisi yok");
        assertThat(result.notes()).contains("1 durağın fiyat bilgisi yok; toplam tahmine dahil edilmedi.");
        assertThat(result.notes()).noneMatch(n -> n.contains("bütçeyi"));
    }

    @Test
    void withABudgetAKnownPriceIsPreferredOverAnUnknownOneOfEqualQuality() {
        Place unknown = place(1, "Unknown", PlaceCategory.CAFE, true, 4.5, 0);
        unknown.setEstimatedCost(null);
        add(unknown, 200);
        add(place(2, "Known", PlaceCategory.CAFE, true, 4.5, 80), 200);

        PlanResult result = planner.plan(request(List.of(PlanningSlot.at(StopType.COFFEE, LocalTime.of(10, 0))),
                500, false));

        assertThat(result.stops()).extracting(s -> s.place().getName()).containsExactly("Known");
        assertThat(result.notes()).noneMatch(n -> n.contains("fiyat bilgisi yok"));
    }

    private void add(Place place, double distance) {
        places.put(place.getId(), place);
        candidates.computeIfAbsent(place.getCategory(), c -> new ArrayList<>())
                .add(new Candidate(place.getId(), distance));
    }

    private PlanningRequest request(List<PlanningSlot> slots, Integer budget, boolean assumeWet) {
        return new PlanningRequest(40.99, 29.02, DAY, LocalTime.of(9, 0), LocalTime.of(22, 0),
                1, budget, WalkingTolerance.MEDIUM, List.of(), slots, Set.of(), assumeWet);
    }

    private static WeatherForecast forecastRainingUntil(int hour) {
        List<HourlyWeather> hourly = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            boolean rain = h < hour;
            hourly.add(new HourlyWeather(DAY.atTime(h, 0), 20, 20, rain ? 90 : 0, rain ? 2 : 0, 10, rain ? 63 : 0));
        }
        return new WeatherForecast(DAY, null, hourly);
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

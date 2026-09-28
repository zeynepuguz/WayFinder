package com.nomi.wayfinder.service;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteResponse;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularStop;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteStartRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.planning.PlaceScorer;
import com.nomi.wayfinder.planning.PopularTheme;
import com.nomi.wayfinder.planning.RoutePlanner;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.repository.RouteRepository;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static com.nomi.wayfinder.TestPlaces.openEveryDay;
import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Popular routes: real RoutePlanner over a small fake Ankara (Çankaya), only the database and weather are faked.
 * There are museums, cafes, restaurants and a dessert shop, but no parks / viewpoints.
 */
class PopularRouteServiceTest {

    // Sunday 27 September 2026, 20:00 in Istanbul
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T17:00:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final LocalDate TOMORROW = LocalDate.of(2026, 9, 28);
    private static final double KIZILAY_LAT = 39.9208;
    private static final double KIZILAY_LON = 32.8541;

    private final Map<Long, Place> places = new LinkedHashMap<>();
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private PopularRouteService service;

    @BeforeEach
    void setUp() {
        add(place(1, "Anıtkabir", PlaceCategory.ATTRACTION, false, 4.9, 0, "history"), 39.9250, 32.8370);
        add(place(2, "Etnografya Müzesi", PlaceCategory.MUSEUM, true, 4.6, 0, "history", "museum"), 39.9340, 32.8520);
        add(place(3, "Anadolu Medeniyetleri Müzesi", PlaceCategory.MUSEUM, true, 4.8, 0, "history"), 39.9385, 32.8620);
        add(unknownPrice(place(4, "Kızılay Kahvecisi", PlaceCategory.CAFE, true, 0, 0)), 39.9215, 32.8550);
        add(unknownPrice(place(5, "Tunalı Kahve", PlaceCategory.CAFE, true, 0, 0)), 39.9100, 32.8600);
        add(place(6, "Kahvaltı Evi", PlaceCategory.BREAKFAST, true, 4.3, 250, "breakfast"), 39.9200, 32.8530);
        add(place(7, "Ankara Lokantası", PlaceCategory.RESTAURANT, true, 4.4, 300), 39.9190, 32.8560);
        add(place(8, "Akşam Sofrası", PlaceCategory.RESTAURANT, true, 4.2, 400), 39.9230, 32.8500);
        add(place(9, "Tatlıcı", PlaceCategory.DESSERT, true, 4.5, 120, "dessert"), 39.9205, 32.8570);

        PlaceRepository repository = mock(PlaceRepository.class);
        when(repository.findCandidates(anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(), anyInt()))
                .thenAnswer(inv -> {
                    double lat = inv.getArgument(0);
                    double lon = inv.getArgument(1);
                    double radius = inv.getArgument(2);
                    Collection<String> categories = inv.getArgument(3);
                    String tag = inv.getArgument(4);
                    List<PlaceDistance> result = new ArrayList<>();
                    for (Place p : places.values()) {
                        double d = meters(lat, lon, p.getLatitude(), p.getLongitude());
                        if (d <= radius && (categories.contains(p.getCategory().name()) || p.hasTag(tag))) {
                            result.add(distance(p.getId(), d));
                        }
                    }
                    result.sort(Comparator.comparing(PlaceDistance::getDistanceMeters));
                    return result;
                });
        when(repository.findByIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(places::get).toList();
        });
        WeatherService weather = mock(WeatherService.class);
        when(weather.getForecast(anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());
        RoutePlanner planner = new RoutePlanner(repository, new PlaceScorer(), weather);

        CityService cities = mock(CityService.class);
        when(cities.findBySlug("ankara")).thenReturn(Optional.of(new CityService.City(2, "ankara", "Ankara", 39.93, 32.86)));
        when(cities.findBySlug("nowhere")).thenReturn(Optional.empty());
        DistrictService districts = mock(DistrictService.class);
        when(districts.findBySlug(2, "cankaya"))
                .thenReturn(Optional.of(new DistrictService.District(20, "Çankaya", KIZILAY_LAT, KIZILAY_LON)));

        when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
        RouteService routeService = new RouteService(routeRepository, planner, mock(UserService.class),
                new RouteMapper(), CLOCK);
        service = new PopularRouteService(planner, cities, districts, routeService, CLOCK);
    }

    @Test
    void themesWithAtLeastThreeRealStopsStartingAtTheDistrict() {
        List<PopularRouteResponse> routes = service.popularRoutes("ankara", "cankaya", TOMORROW);

        // No parks or viewpoints in this Ankara: that theme is left out
        assertThat(routes).extracting(PopularRouteResponse::theme)
                .containsExactly(PopularTheme.HISTORY, PopularTheme.FOOD, PopularTheme.COFFEE_DESSERT);
        PopularRouteResponse history = routes.getFirst();
        assertThat(history.startLabel()).isEqualTo("Çankaya");
        assertThat(history.startLatitude()).isEqualTo(KIZILAY_LAT);
        assertThat(history.date()).isEqualTo(TOMORROW);
        assertThat(history.title()).isEqualTo("Tarihi yerler");
        assertThat(history.stops()).hasSize(4);
        assertThat(history.stops().getFirst().time()).matches("\\d\\d:\\d\\d").isGreaterThanOrEqualTo("10:00");
        // History stops are museums / attractions only, plus the coffee break
        assertThat(history.stops()).extracting(s -> s.place().category()).containsOnly(
                PlaceCategory.MUSEUM, PlaceCategory.ATTRACTION, PlaceCategory.CAFE);
        // Free sights (0 TL) are known prices; the cafe's is unknown
        assertThat(history.estimatedCostPerPerson()).isEqualTo(0);
        assertThat(history.unknownPriceStops()).isEqualTo(1);

        PopularRouteResponse food = routes.get(1);
        assertThat(food.stops()).extracting(PopularStop::type).containsExactly(
                StopType.BREAKFAST, StopType.LUNCH, StopType.DESSERT, StopType.DINNER);
        assertThat(food.estimatedCostPerPerson()).isEqualTo(250 + 300 + 120 + 400);
        assertThat(food.unknownPriceStops()).isZero();
        assertThat(food.totalWalkingMinutes())
                .isEqualTo(food.stops().stream().mapToInt(PopularStop::walkingMinutes).sum());

        // Two cafes with unknown prices: coffee + sightseeing + dessert + coffee
        PopularRouteResponse coffee = routes.get(2);
        assertThat(coffee.stops()).hasSize(4);
        assertThat(coffee.estimatedCostPerPerson()).isEqualTo(120);
        assertThat(coffee.unknownPriceStops()).isEqualTo(2);
    }

    @Test
    void withoutADistrictTheRouteStartsAtTheCityLabelPoint() {
        List<PopularRouteResponse> routes = service.popularRoutes("ankara", null, TOMORROW);

        assertThat(routes).isNotEmpty();
        assertThat(routes.getFirst().startLabel()).isEqualTo("Ankara");
        assertThat(routes.getFirst().startLatitude()).isEqualTo(39.93);
    }

    @Test
    void startedRouteMatchesThePreview() {
        PopularRouteResponse preview = service.popularRoutes("ankara", "cankaya", TOMORROW).get(1);

        RouteResponse route = service.start(7L,
                new PopularRouteStartRequest("ankara", "cankaya", PopularTheme.FOOD, TOMORROW));

        assertThat(route.title()).isEqualTo("Çankaya: Lezzet turu");
        assertThat(route.date()).isEqualTo(TOMORROW);
        assertThat(route.startLatitude()).isEqualTo(KIZILAY_LAT);
        assertThat(route.partySize()).isEqualTo(1);
        assertThat(route.budget()).isNull();
        assertThat(route.walkingTolerance()).isEqualTo(WalkingTolerance.MEDIUM);
        assertThat(route.startTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(route.stops()).extracting(s -> s.place().id())
                .containsExactlyElementsOf(preview.stops().stream().map(s -> s.place().id()).toList());
        assertThat(route.stops()).extracting(s -> s.plannedStart().toString())
                .containsExactlyElementsOf(preview.stops().stream().map(PopularStop::time).toList());
        assertThat(route.totalEstimatedCost()).isEqualTo(preview.estimatedCostPerPerson());
        verify(routeRepository).save(argThat(r -> r.getUserId().equals(7L)));
    }

    @Test
    void unknownCityOrDistrictIs404AndPastDaysAre400() {
        assertThatThrownBy(() -> service.popularRoutes("nowhere", null, TOMORROW))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.popularRoutes("ankara", "kadikoy", TOMORROW))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.dayOrToday(LocalDate.of(2026, 9, 26)))
                .isInstanceOf(BusinessException.class);
        assertThat(service.dayOrToday(null)).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    private static Place unknownPrice(Place place) {
        place.setEstimatedCost(null);
        place.setRating(null);
        return place;
    }

    private void add(Place place, double lat, double lon) {
        place.setCoordinates(lat, lon);
        openEveryDay(place, "08:00", "23:30");
        places.put(place.getId(), place);
    }

    private static PlaceDistance distance(long id, double meters) {
        return new PlaceDistance() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Double getDistanceMeters() {
                return meters;
            }
        };
    }

    private static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
    }
}

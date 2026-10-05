package com.nomi.wayfinder.service;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteResponse;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteStartRequest;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularStop;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.geo.GeoMath;
import com.nomi.wayfinder.planning.PlaceScorer;
import com.nomi.wayfinder.planning.PopularRouteBuilder;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Area;
import com.nomi.wayfinder.planning.RoutePlanner;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.repository.PopularSightRepository;
import com.nomi.wayfinder.repository.RouteRepository;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;

import static com.nomi.wayfinder.TestPlaces.openEveryDay;
import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Popular routes over a small real-coordinate İstanbul: the historic peninsula (Sultanahmet) and Galata on the other
 * side of the Golden Horn, plus places to eat. Real RoutePlanner and PopularRouteBuilder; only the database and the
 * weather are faked. Popularity values are made up for the test (the real ones come from Wikipedia).
 */
class PopularRouteServiceTest {

    // Sunday 27 September 2026, 20:00 in Istanbul
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T17:00:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 29);
    private static final long ISTANBUL = 1;
    private static final long BEYOGLU = 11;

    private final Map<Long, Place> places = new LinkedHashMap<>();
    private final Map<Long, Long> districtOf = new HashMap<>();
    private final List<Area> areas = List.of(
            new Area("Sultan Ahmet", "suburb", 41.0055, 28.9760),
            new Area("Cağaloğlu", "quarter", 41.0110, 28.9750),
            new Area("Galata", "quarter", 41.0250, 28.9740),
            new Area("Karaköy", "quarter", 41.0230, 28.9770));
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private PopularRouteService service;

    @BeforeEach
    void setUp() {
        sight(1, "Ayasofya", PlaceCategory.ATTRACTION, 22, 41.0086, 28.9802, "religious");
        sight(2, "Sultanahmet Camii", PlaceCategory.ATTRACTION, 20, 41.0054, 28.9768, "religious");
        Place topkapi = sight(3, "Topkapı Sarayı", PlaceCategory.MUSEUM, 21, 41.0115, 28.9834);
        sight(4, "Yerebatan Sarnıcı", PlaceCategory.ATTRACTION, 17, 41.0084, 28.9779);
        sight(5, "Gülhane Parkı", PlaceCategory.PARK, 12, 41.0133, 28.9813);
        sight(6, "İstanbul Arkeoloji Müzeleri", PlaceCategory.MUSEUM, 14, 41.0117, 28.9814);
        sight(7, "Galata Kulesi", PlaceCategory.ATTRACTION, 19, 41.0256, 28.9741);
        sight(8, "Pera Müzesi", PlaceCategory.MUSEUM, 11, 41.0317, 28.9749);
        sight(9, "İstanbul Modern", PlaceCategory.MUSEUM, 12, 41.0263, 28.9800);
        sight(10, "Kamondo Merdivenleri", PlaceCategory.ATTRACTION, 8, 41.0240, 28.9737);
        // Topkapı is closed on Tuesdays
        openOn(topkapi, "09:00", "18:00", 1, 3, 4, 5, 6, 7);
        for (long id = 7; id <= 10; id++) {
            districtOf.put(id, BEYOGLU);
        }

        food(20, "Sultanahmet Köftecisi", PlaceCategory.RESTAURANT, 4.5, 41.0080, 28.9760);
        food(21, "Hafız Mustafa", PlaceCategory.DESSERT, 4.6, 41.0105, 28.9775);
        food(22, "Gülhane Kahvesi", PlaceCategory.CAFE, 4.3, 41.0125, 28.9820);
        food(23, "Karaköy Lokantası", PlaceCategory.RESTAURANT, 4.6, 41.0245, 28.9790);
        food(24, "Galata Kahve", PlaceCategory.CAFE, 4.4, 41.0250, 28.9745);
        food(25, "Divan Yolu Kafe", PlaceCategory.CAFE, 4.0, 41.0082, 28.9740);

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
                        double d = GeoMath.meters(lat, lon, p.getLatitude(), p.getLongitude());
                        if (d <= radius && (categories.contains(p.getCategory().name()) || p.hasTag(tag))) {
                            result.add(distance(p.getId(), d));
                        }
                    }
                    result.sort(Comparator.comparing(PlaceDistance::getDistanceMeters));
                    return result;
                });
        when(repository.findByIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(places::get).filter(Objects::nonNull).toList();
        });
        when(repository.findWithOpeningHoursById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(places.get(inv.<Long>getArgument(0))));
        when(repository.distanceTo(anyLong(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            Place p = places.get(inv.<Long>getArgument(0));
            return GeoMath.meters(inv.getArgument(1), inv.getArgument(2), p.getLatitude(), p.getLongitude());
        });
        WeatherService weather = mock(WeatherService.class);
        when(weather.getForecast(anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());
        RoutePlanner planner = new RoutePlanner(repository, new PlaceScorer(), weather);

        PopularSightRepository sights = mock(PopularSightRepository.class);
        when(sights.seedScores(anyLong(), any(), anyInt())).thenAnswer(inv -> {
            Long district = inv.getArgument(1);
            Map<Long, Double> scores = new LinkedHashMap<>();
            places.values().stream()
                    .filter(p -> p.getPopularity() != null)
                    .filter(p -> district == null || district.equals(districtOf.get(p.getId())))
                    .sorted(Comparator.comparing(Place::getPopularity).reversed())
                    .forEach(p -> scores.put(p.getId(), p.getPopularity()));
            return scores;
        });
        when(sights.areas(anyLong(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> areas.stream()
                .filter(a -> inBox(a.latitude(), a.longitude(), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4)))
                .toList());
        when(sights.placeNames(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> places.values().stream()
                .filter(p -> inBox(p.getLatitude(), p.getLongitude(), inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3)))
                .map(Place::getName)
                .toList());

        CityService cities = mock(CityService.class);
        when(cities.findBySlug("istanbul")).thenReturn(Optional.of(new CityService.City(ISTANBUL, "istanbul", "İstanbul", 41.0, 28.97)));
        when(cities.findBySlug("nowhere")).thenReturn(Optional.empty());
        DistrictService districts = mock(DistrictService.class);
        when(districts.findIdBySlug(ISTANBUL, "beyoglu")).thenReturn(Optional.of(BEYOGLU));
        when(districts.findIdBySlug(ISTANBUL, "cankaya")).thenReturn(Optional.empty());

        when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
        RouteService routeService = new RouteService(routeRepository, planner, mock(UserService.class),
                new RouteMapper(), CLOCK);
        service = new PopularRouteService(planner, cities, districts, routeService, repository, sights, CLOCK, null);
    }

    @Test
    void routesAreWalkableGroupsOfTheMostPopularSightsWithMealsInBetween() {
        List<PopularRouteResponse> routes = service.popularRoutes("istanbul", null, MONDAY);

        assertThat(routes).extracting(PopularRouteResponse::title)
                .containsExactly("Sultan Ahmet ve çevresi", "Galata ve çevresi");
        PopularRouteResponse peninsula = routes.getFirst();
        assertThat(peninsula.popularityNote()).isEqualTo("Wikipedia’da en çok okunan yerler");
        assertThat(peninsula.description()).isEqualTo("Bu bölgenin en çok ilgi gören yerleri, yürüme sırasıyla.");
        assertThat(peninsula.date()).isEqualTo(MONDAY);

        // The five most popular sights of the group (Gülhane, the sixth, is left out), mixed with meals
        List<String> sights = peninsula.stops().stream().filter(s -> s.type() == StopType.SIGHTSEEING)
                .map(s -> s.place().name()).toList();
        assertThat(sights).containsExactlyInAnyOrder("Ayasofya", "Sultanahmet Camii", "Topkapı Sarayı",
                "Yerebatan Sarnıcı", "İstanbul Arkeoloji Müzeleri");
        assertThat(peninsula.stops()).extracting(PopularStop::type).contains(StopType.LUNCH)
                .containsAnyOf(StopType.COFFEE, StopType.DESSERT);

        // Starts at the first sight at 09:30; lunch not before noon; times only go forward
        PopularStop first = peninsula.stops().getFirst();
        assertThat(first.type()).isEqualTo(StopType.SIGHTSEEING);
        assertThat(first.time()).isEqualTo("09:30");
        assertThat(first.walkingMinutes()).isZero();
        assertThat(peninsula.startLabel()).isEqualTo(first.place().name());
        PopularStop lunch = peninsula.stops().stream().filter(s -> s.type() == StopType.LUNCH).findFirst().orElseThrow();
        assertThat(lunch.time()).isGreaterThanOrEqualTo("12:00");
        assertThat(lunch.typeLabel()).isEqualTo("Öğle yemeği");
        List<String> times = peninsula.stops().stream().map(PopularStop::time).toList();
        assertThat(times).isSorted();

        // Walkable: every leg short, the whole day well under an hour and a half
        assertThat(peninsula.stops()).allMatch(s -> s.distanceMeters() <= 1200);
        assertThat(peninsula.totalWalkingMinutes()).isLessThanOrEqualTo(90)
                .isEqualTo(peninsula.stops().stream().mapToInt(PopularStop::walkingMinutes).sum());

        // Galata is across the Golden Horn: its own route
        assertThat(routes.get(1).stops()).filteredOn(s -> s.type() == StopType.SIGHTSEEING)
                .extracting(s -> s.place().name())
                .containsExactlyInAnyOrder("Galata Kulesi", "Pera Müzesi", "İstanbul Modern", "Kamondo Merdivenleri");

        // Keys are stable
        assertThat(service.popularRoutes("istanbul", null, MONDAY)).extracting(PopularRouteResponse::key)
                .containsExactlyElementsOf(routes.stream().map(PopularRouteResponse::key).toList());
    }

    @Test
    void sightsClosedThatDayLeaveRoomForTheNextOne() {
        PopularRouteResponse peninsula = service.popularRoutes("istanbul", null, TUESDAY).getFirst();
        assertThat(peninsula.stops()).extracting(s -> s.place().name())
                .doesNotContain("Topkapı Sarayı")
                .contains("Gülhane Parkı");
    }

    @Test
    void aDistrictGetsTheRoutesOfItsOwnSights() {
        List<PopularRouteResponse> routes = service.popularRoutes("istanbul", "beyoglu", MONDAY);
        assertThat(routes).extracting(PopularRouteResponse::title).containsExactly("Galata ve çevresi");
    }

    @Test
    void startedRouteIsExactlyThePreview() {
        PopularRouteResponse preview = service.popularRoutes("istanbul", null, MONDAY).getFirst();

        RouteResponse route = service.start(7L, new PopularRouteStartRequest("istanbul", null, preview.key(), MONDAY));

        assertThat(route.title()).isEqualTo("Sultan Ahmet ve çevresi");
        assertThat(route.date()).isEqualTo(MONDAY);
        assertThat(route.startLatitude()).isEqualTo(preview.startLatitude());
        assertThat(route.partySize()).isEqualTo(1);
        assertThat(route.budget()).isNull();
        assertThat(route.walkingTolerance()).isEqualTo(WalkingTolerance.MEDIUM);
        assertThat(route.startTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(route.stops()).extracting(s -> s.place().id())
                .containsExactlyElementsOf(preview.stops().stream().map(s -> s.place().id()).toList());
        assertThat(route.stops()).extracting(s -> s.plannedStart().toString())
                .containsExactlyElementsOf(preview.stops().stream().map(PopularStop::time).toList());
        verify(routeRepository).save(argThat(r -> r.getUserId().equals(7L)));

        assertThatThrownBy(() -> service.start(7L, new PopularRouteStartRequest("istanbul", null, "nope", MONDAY)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unknownCityOrDistrictIs404AndPastDaysAre400() {
        assertThatThrownBy(() -> service.popularRoutes("nowhere", null, MONDAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.popularRoutes("istanbul", "cankaya", MONDAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.dayOrToday(LocalDate.of(2026, 9, 26)))
                .isInstanceOf(BusinessException.class);
        assertThat(service.dayOrToday(null)).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    private Place sight(long id, String name, PlaceCategory category, double popularity, double lat, double lon,
                        String... tags) {
        Place p = place(id, name, category, category == PlaceCategory.MUSEUM, 0, 0, tags);
        p.setRating(null);
        ReflectionTestUtils.setField(p, "popularity", popularity);
        p.setCoordinates(lat, lon);
        openEveryDay(p, "09:00", "18:00");
        places.put(id, p);
        return p;
    }

    private void food(long id, String name, PlaceCategory category, double rating, double lat, double lon) {
        Place p = place(id, name, category, true, rating, 300);
        p.setCoordinates(lat, lon);
        openEveryDay(p, "08:00", "23:30");
        places.put(id, p);
    }

    private static void openOn(Place place, String opens, String closes, int... days) {
        List<PlaceOpeningHours> hours = new ArrayList<>();
        for (int day : days) {
            hours.add(new PlaceOpeningHours(day, LocalTime.parse(opens), LocalTime.parse(closes)));
        }
        place.replaceOpeningHours(hours);
    }

    private static boolean inBox(double lat, double lon, double south, double west, double north, double east) {
        return lat >= south && lat <= north && lon >= west && lon <= east;
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
}

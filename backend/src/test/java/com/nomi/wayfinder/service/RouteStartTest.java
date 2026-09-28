package com.nomi.wayfinder.service;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.dto.RouteDtos.StartMode;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.planning.PlanResult;
import com.nomi.wayfinder.planning.PlanningRequest;
import com.nomi.wayfinder.planning.RoutePlanner;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.repository.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// "Yeni rota": start at the user's position or at a chosen city / district (its most popular sight, else its centre)
class RouteStartTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T06:00:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final long ISTANBUL = 34;
    private static final long KADIKOY = 7;
    private static final long FATIH = 8;

    private RouteStartService startService;
    private RoutePlanner planner;
    private PlaceRepository placeRepository;
    private RouteService routeService;

    @BeforeEach
    void setUp() {
        CityService cities = mock(CityService.class);
        when(cities.findBySlug("istanbul")).thenReturn(Optional.of(new CityService.City(ISTANBUL, "istanbul", "İstanbul", 41.0, 28.97)));
        when(cities.findBySlug("nowhere")).thenReturn(Optional.empty());
        DistrictService districts = mock(DistrictService.class);
        when(districts.findBySlug(ISTANBUL, "kadikoy"))
                .thenReturn(Optional.of(new DistrictService.District(KADIKOY, "Kadıköy", 40.9913, 29.0246)));
        when(districts.findBySlug(ISTANBUL, "fatih"))
                .thenReturn(Optional.of(new DistrictService.District(FATIH, "Fatih", 41.0193, 28.9479)));
        when(districts.findBySlug(ISTANBUL, "cankaya")).thenReturn(Optional.empty());

        startService = new RouteStartService(cities, districts, mock(JdbcTemplate.class)) {
            @Override
            Optional<Start> bestSight(String column, long id) {
                // Fatih has a popular sight with cafés around; Kadıköy (in this test) has none
                return id == FATIH && column.equals("p.district_id")
                        ? Optional.of(new Start(41.0086, 28.9802, StartKind.SIGHT, "Ayasofya"))
                        : Optional.empty();
            }
        };

        planner = mock(RoutePlanner.class);
        when(planner.plan(any())).thenReturn(new PlanResult(List.of(), List.of(), Optional.empty(), null));
        placeRepository = mock(PlaceRepository.class);
        RouteRepository routes = mock(RouteRepository.class);
        when(routes.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
        UserService users = mock(UserService.class);
        when(users.getPreferences(anyLong())).thenReturn(new UserPreferences(1L));
        routeService = new RouteService(routes, planner, users, new RouteMapper(), CLOCK, startService, placeRepository);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void startModeDefaultsToTheGivenPositionElseTheArea() {
        assertThat(RouteStartService.mode(request(40.99, 29.02, null, null, null))).isEqualTo(StartMode.LOCATION);
        assertThat(RouteStartService.mode(request(null, null, "istanbul", "kadikoy", null))).isEqualTo(StartMode.AREA);
        assertThat(RouteStartService.mode(request(40.99, 29.02, "istanbul", "kadikoy", null))).isEqualTo(StartMode.LOCATION);
        assertThat(RouteStartService.mode(request(40.99, 29.02, "istanbul", "kadikoy", StartMode.AREA)))
                .isEqualTo(StartMode.AREA);
    }

    @Test
    void areaStartsAtTheMostPopularSightElseTheCentre() {
        RouteStartService.Start fatih = startService.resolve(request(null, null, "istanbul", "fatih", StartMode.AREA));
        assertThat(fatih.label()).isEqualTo("Ayasofya");
        assertThat(fatih.kind()).isEqualTo(StartKind.SIGHT);
        assertThat(fatih.latitude()).isEqualTo(41.0086);

        RouteStartService.Start kadikoy = startService.resolve(request(40.0, 30.0, "istanbul", "kadikoy", StartMode.AREA));
        assertThat(kadikoy.kind()).isEqualTo(StartKind.DISTRICT);
        assertThat(kadikoy.latitude()).isEqualTo(40.9913);
        assertThat(RouteStartService.label(kadikoy.kind(), kadikoy.label())).isEqualTo("Kadıköy merkezi");

        RouteStartService.Start city = startService.resolve(request(null, null, "istanbul", null, null));
        assertThat(city.kind()).isEqualTo(StartKind.CITY);
        assertThat(RouteStartService.label(city.kind(), city.label())).isEqualTo("İstanbul merkezi");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(RouteStartService.label(StartKind.DISTRICT, "Kadıköy")).isEqualTo("Central Kadıköy");
        assertThat(RouteStartService.label(StartKind.LOCATION, null)).isEqualTo("Your location");
        assertThat(RouteStartService.label(null, null)).isEqualTo("Your location");
    }

    @Test
    void unknownSlugsAre404AndMissingStartsAre400() {
        assertThatThrownBy(() -> startService.resolve(request(null, null, "nowhere", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> startService.resolve(request(null, null, "istanbul", "cankaya", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> startService.resolve(request(null, null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("latitude and longitude");
        assertThatThrownBy(() -> startService.resolve(request(40.99, 29.02, null, null, StartMode.AREA)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("city is required");
    }

    @Test
    void anAreaRouteIsPlannedFromItsStartAndSaysWhereItStarts() {
        RouteResponse route = routeService.planRoute(1L, request(null, null, "istanbul", "fatih", StartMode.AREA));

        ArgumentCaptor<PlanningRequest> planned = ArgumentCaptor.forClass(PlanningRequest.class);
        verify(planner).plan(planned.capture());
        assertThat(planned.getValue().startLatitude()).isEqualTo(41.0086);
        assertThat(route.startLabel()).isEqualTo("Ayasofya");
        assertThat(route.startLatitude()).isEqualTo(41.0086);
        // Not counted for an area start: the area was chosen on purpose
        verify(placeRepository, never()).countPlannable(anyDouble(), anyDouble(), anyDouble(), anyCollection());
    }

    @Test
    void aStartWithFewPlacesAroundSuggestsChoosingAnArea() {
        when(placeRepository.countPlannable(anyDouble(), anyDouble(), anyDouble(), anyCollection())).thenReturn(3L);
        RouteResponse sparse = routeService.planRoute(1L, request(40.8255, 29.3737, null, null, null));
        assertThat(sparse.startLabel()).isEqualTo("Konumun");
        assertThat(sparse.notes().getFirst()).contains("çok az mekan var (3)").contains("şehir ve ilçe seçebilirsin");

        when(placeRepository.countPlannable(anyDouble(), anyDouble(), anyDouble(), anyCollection())).thenReturn(40L);
        RouteResponse busy = routeService.planRoute(1L, request(40.99, 29.02, null, null, null));
        assertThat(busy.notes()).isEmpty();
    }

    private static RoutePlanRequest request(Double lat, Double lon, String city, String district, StartMode mode) {
        return new RoutePlanRequest(lat, lon, LocalDate.of(2026, 9, 28), LocalTime.of(9, 0), LocalTime.of(22, 0),
                1, 950, WalkingTolerance.LOW,
                List.of(StopType.BREAKFAST, StopType.SIGHTSEEING, StopType.COFFEE, StopType.DESSERT, StopType.DINNER),
                List.of("deniz", "doğa", "uygun fiyat"), null, city, district, mode);
    }
}

package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.RouteDtos.ReplanRequest;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.planning.*;
import com.nomi.wayfinder.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// "Başka bir yerle değiştir": swapping the same stop again never brings back a place swapped out before, and the
// user's "neye göre?" answer reaches the planner
class ReplaceStopTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T06:00:00Z"), ZoneId.of("Europe/Istanbul"));

    private final AtomicLong ids = new AtomicLong(100);
    private RoutePlanner planner;
    private RouteService routeService;
    private Route route;
    // The planner's answer to each call: first B, then C
    private final List<Place> answers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        planner = mock(RoutePlanner.class);
        when(planner.plan(any())).thenAnswer(inv -> {
            Place next = answers.removeFirst();
            return new PlanResult(List.of(new PlannedStop(next, StopType.LUNCH, LocalTime.of(12, 30),
                    LocalTime.of(13, 30), 100, 2, List.of())), List.of(), Optional.empty(), null);
        });
        RouteRepository routes = mock(RouteRepository.class);
        when(routes.saveAndFlush(any(Route.class))).thenAnswer(inv -> {
            Route saved = inv.getArgument(0);
            saved.getStops().forEach(s -> {
                if (s.getId() == null) {
                    ReflectionTestUtils.setField(s, "id", ids.incrementAndGet());
                }
            });
            return saved;
        });
        routeService = new RouteService(routes, planner, mock(UserService.class), new RouteMapper(), CLOCK);

        route = new Route();
        route.setDate(LocalDate.of(2026, 10, 2));
        route.setStartTime(LocalTime.of(10, 0));
        route.setEndTime(LocalTime.of(18, 0));
        route.setStartLocation(39.92, 32.85);
        route.setWalkingTolerance(WalkingTolerance.MEDIUM);
        route.setStatus(RouteStatus.ACTIVE);
        RouteStop stop = new RouteStop();
        stop.setPlace(place(1, "Pilav Kralı"));
        stop.setStopType(StopType.LUNCH);
        stop.setPlannedStart(LocalTime.of(12, 30));
        stop.setPlannedEnd(LocalTime.of(13, 30));
        stop.setStatus(StopStatus.PLANNED);
        ReflectionTestUtils.setField(stop, "id", ids.incrementAndGet());
        route.replaceStops(new ArrayList<>(List.of(stop)));
    }

    @Test
    void secondSwapDoesNotBringTheFirstPlaceBack() {
        answers.add(place(2, "Kebapçı Ali"));
        answers.add(place(3, "Balıkçı Hasan"));

        routeService.replanRoute(route, swap(null));
        routeService.replanRoute(route, swap(null));

        ArgumentCaptor<PlanningRequest> requests = ArgumentCaptor.forClass(PlanningRequest.class);
        verify(planner, times(2)).plan(requests.capture());
        // The second swap (of place 2) excludes place 1 too
        assertThat(requests.getAllValues().get(1).excludedPlaceIds()).contains(1L, 2L);
        assertThat(route.getRejectedPlaceIds()).containsExactly(1L, 2L);
    }

    @Test
    void theWishReachesTheSwappedSlot() {
        answers.add(place(2, "Kebapçı Ali"));

        routeService.replanRoute(route, swap("kebap olsun"));

        ArgumentCaptor<PlanningRequest> request = ArgumentCaptor.forClass(PlanningRequest.class);
        verify(planner).plan(request.capture());
        PlanningSlot slot = request.getValue().slots().getFirst();
        assertThat(slot.pinnedPlaceId()).isNull();
        assertThat(slot.wish().words()).contains("kebap");
    }

    private ReplanRequest swap(String wish) {
        Long stopId = route.getStops().stream().filter(s -> s.getStatus() == StopStatus.PLANNED).findFirst()
                .orElseThrow().getId();
        return new ReplanRequest(ReplanType.REPLACE_STOP, 39.92, 32.85, stopId, null, null, wish);
    }

    private static Place place(long id, String name) {
        Place place = new Place();
        ReflectionTestUtils.setField(place, "id", id);
        place.setName(name);
        place.setCategory(PlaceCategory.RESTAURANT);
        place.setCoordinates(39.92, 32.85);
        return place;
    }
}

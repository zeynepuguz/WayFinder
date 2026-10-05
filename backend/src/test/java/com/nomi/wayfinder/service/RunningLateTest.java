package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.RouteDtos.ReplanRequest;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.planning.*;
import com.nomi.wayfinder.repository.RouteRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// "Geciktik": the same places, planned again from now
class RunningLateTest {

    // 12:50 in Istanbul; the lunch stop was planned for 12:30
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T09:50:00Z"), ZoneId.of("Europe/Istanbul"));

    private final RoutePlanner planner = mock(RoutePlanner.class);
    private final RouteService routeService = new RouteService(mock(RouteRepository.class), planner,
            mock(UserService.class), new RouteMapper(), CLOCK);

    @Test
    void keepsEveryStopPinnedAndPlansFromNow() {
        Route route = route(LocalDate.of(2026, 10, 2));
        Place same = route.getStops().getFirst().getPlace();
        when(planner.plan(any())).thenReturn(new PlanResult(List.of(new PlannedStop(same, StopType.LUNCH,
                LocalTime.of(13, 5), LocalTime.of(14, 5), 0, 0, List.of())), List.of(), Optional.empty(), null));

        List<String> changes = routeService.replanRoute(route, late());

        ArgumentCaptor<PlanningRequest> request = ArgumentCaptor.forClass(PlanningRequest.class);
        verify(planner).plan(request.capture());
        assertThat(request.getValue().startTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(request.getValue().slots()).extracting(PlanningSlot::pinnedPlaceId).containsExactly(1L);
        assertThat(changes).containsExactly("Mekanlar aynı kaldı, saatleri güncelledim.");
        assertThat(route.getStops().getFirst().getPlannedStart()).isEqualTo(LocalTime.of(13, 5));
    }

    @Test
    void onlyTodaysRouteCanBeShifted() {
        Route tomorrow = route(LocalDate.of(2026, 10, 3));

        assertThatThrownBy(() -> routeService.replanRoute(tomorrow, late())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(planner);
    }

    private static ReplanRequest late() {
        return new ReplanRequest(ReplanType.RUNNING_LATE, 39.92, 32.85, null, null, null);
    }

    private static Route route(LocalDate date) {
        Route route = new Route();
        route.setDate(date);
        route.setStartTime(LocalTime.of(10, 0));
        route.setEndTime(LocalTime.of(18, 0));
        route.setStartLocation(39.92, 32.85);
        route.setWalkingTolerance(WalkingTolerance.MEDIUM);
        route.setStatus(RouteStatus.ACTIVE);
        Place place = new Place();
        ReflectionTestUtils.setField(place, "id", 1L);
        place.setName("Pilav Kralı");
        place.setCategory(PlaceCategory.RESTAURANT);
        place.setCoordinates(39.92, 32.85);
        RouteStop stop = new RouteStop();
        stop.setPlace(place);
        stop.setStopType(StopType.LUNCH);
        stop.setPlannedStart(LocalTime.of(12, 30));
        stop.setPlannedEnd(LocalTime.of(13, 30));
        stop.setStatus(StopStatus.PLANNED);
        route.replaceStops(new ArrayList<>(List.of(stop)));
        return route;
    }
}

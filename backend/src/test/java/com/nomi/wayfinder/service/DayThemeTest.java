package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.planning.*;
import com.nomi.wayfinder.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

// Themed days only fill in what the user did not choose, and always say what they cannot promise
class DayThemeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T06:00:00Z"), ZoneId.of("Europe/Istanbul"));

    private final RoutePlanner planner = mock(RoutePlanner.class);
    private RouteService routeService;

    @BeforeEach
    void setUp() {
        when(planner.plan(any())).thenReturn(new PlanResult(List.of(), List.of("Planner note"), Optional.empty(), null));
        RouteRepository routes = mock(RouteRepository.class);
        when(routes.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
        UserService users = mock(UserService.class);
        when(users.getPreferences(anyLong())).thenReturn(new UserPreferences(1L));
        routeService = new RouteService(routes, planner, users, new RouteMapper(), CLOCK);
    }

    @Test
    void rainyDayPlansIndoorsAndKeepsItForReplans() {
        Route route = routeService.createRoute(1L, request(DayTheme.RAINY, null, null));

        PlanningRequest planned = planned();
        assertThat(planned.assumeWet()).isTrue();
        assertThat(planned.interests()).contains("museum", "art");
        assertThat(route.isAssumeWet()).isTrue();
        assertThat(route.getNotes().getFirst()).startsWith("Yağmurlu gün planı");
        assertThat(route.getNotes()).contains("Planner note");
    }

    @Test
    void familyDayWalksLessUnlessTheUserChoseOtherwise() {
        routeService.createRoute(1L, request(DayTheme.FAMILY, null, null));
        assertThat(planned().walkingTolerance()).isEqualTo(WalkingTolerance.LOW);
        assertThat(planned().slots()).extracting(PlanningSlot::type)
                .containsExactly(StopType.SIGHTSEEING, StopType.LUNCH, StopType.SIGHTSEEING, StopType.DESSERT);

        reset(planner);
        when(planner.plan(any())).thenReturn(new PlanResult(List.of(), List.of(), Optional.empty(), null));
        routeService.createRoute(1L, request(DayTheme.FAMILY, WalkingTolerance.HIGH, List.of(StopType.DINNER)));
        assertThat(planned().walkingTolerance()).isEqualTo(WalkingTolerance.HIGH);
        assertThat(planned().slots()).extracting(PlanningSlot::type).containsExactly(StopType.DINNER);
    }

    @Test
    void lowBudgetNeverGuessesPrices() {
        Route route = routeService.createRoute(1L, request(DayTheme.LOW_BUDGET, null, null));

        assertThat(planned().interests()).contains("budget");
        assertThat(planned().budget()).isNull();
        assertThat(route.getNotes().getFirst()).contains("Fiyat her mekan için bilinmiyor");
    }

    private PlanningRequest planned() {
        ArgumentCaptor<PlanningRequest> captor = ArgumentCaptor.forClass(PlanningRequest.class);
        verify(planner, atLeastOnce()).plan(captor.capture());
        return captor.getValue();
    }

    private static RoutePlanRequest request(DayTheme theme, WalkingTolerance walking, List<StopType> stops) {
        return new RoutePlanRequest(40.99, 29.03, null, LocalTime.of(10, 0), LocalTime.of(20, 0), 2, null, walking,
                stops, null, null, null, null, null, null, theme);
    }
}

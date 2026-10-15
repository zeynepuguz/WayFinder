package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "2 kişiyiz, 700 TL, kahvaltı ve kahve" at 20:10 once planned a breakfast at 20:10, then the assistant asked "şimdi
 * mi, başka gün mü?" and "nereden başlayalım?". Now it plans right away: tomorrow morning, from where the user is.
 */
class AssistantBreakfastWhenTest {

    // Tuesday 29 September 2026, 20:10 in Istanbul
    private static final Clock EVENING = Clock.fixed(Instant.parse("2026-09-29T17:10:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final String REQUEST = "2 kişiyiz, 700 TL bütçemiz var, kahvaltı ve kahve istiyoruz";

    private final RouteService routeService = mock(RouteService.class);
    private AssistantService service;

    @BeforeEach
    void setUp() {
        when(routeService.createRoute(anyLong(), any())).thenReturn(new Route());
        ResponseComposer composer = mock(ResponseComposer.class);
        when(composer.breakfastTomorrow()).thenCallRealMethod();
        when(composer.planCreated(any(), any())).thenReturn("plan");
        AreaResolver areas = mock(AreaResolver.class);
        when(areas.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());

        service = new AssistantService(new RuleBasedIntentParser(EVENING), routeService,
                mock(RecommendationService.class), mock(WeatherService.class), composer,
                mock(AssistantMessageRepository.class), AssistantConversationsTest.conversationRepository(), areas, EVENING);
    }

    @Test
    void anEveningBreakfastIsPlannedForTomorrowMorningWithoutAQuestion() {
        AssistantReply reply = service.handle(1L, new AssistantRequest(REQUEST, 40.8, 29.37, null));

        assertThat(reply.reply()).startsWith("Bugünün kahvaltı saati geçtiği için rotayı yarın sabah için planladım")
                .endsWith("Plan");
        ArgumentCaptor<RoutePlanRequest> plan = ArgumentCaptor.forClass(RoutePlanRequest.class);
        verify(routeService).createRoute(eq(1L), plan.capture());
        assertThat(plan.getValue().date()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(plan.getValue().partySize()).isEqualTo(2);
        assertThat(plan.getValue().budget()).isEqualTo(700);
        // No place named: from where the user is
        assertThat(plan.getValue().latitude()).isEqualTo(40.8);
    }

    @Test
    void sayingNowPlansForToday() {
        AssistantReply reply = service.handle(1L, new AssistantRequest("şimdi " + REQUEST, 40.8, 29.37, null));

        assertThat(reply.reply()).isEqualTo("Plan");
        ArgumentCaptor<RoutePlanRequest> plan = ArgumentCaptor.forClass(RoutePlanRequest.class);
        verify(routeService).createRoute(eq(1L), plan.capture());
        assertThat(plan.getValue().date()).isNull();
    }
}

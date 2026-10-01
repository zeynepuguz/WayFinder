package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.entity.AssistantMessage;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// "2 kişiyiz, 700 TL, kahvaltı ve kahve" at 20:10 planned a breakfast at 20:10: now the assistant asks first
class AssistantBreakfastWhenTest {

    // Tuesday 29 September 2026, 20:10 in Istanbul
    private static final Clock EVENING = Clock.fixed(Instant.parse("2026-09-29T17:10:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final String REQUEST = "2 kişiyiz, 700 TL bütçemiz var, kahvaltı ve kahve istiyoruz";

    private final RouteService routeService = mock(RouteService.class);
    private final List<AssistantMessage> saved = new ArrayList<>();
    private AssistantService service;

    @BeforeEach
    void setUp() {
        AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
        when(messages.save(any(AssistantMessage.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(messages.findByConversationIdOrderByCreatedAtDescIdDesc(any(), any(Pageable.class))).thenAnswer(inv -> {
            List<AssistantMessage> newestFirst = new ArrayList<>(saved);
            java.util.Collections.reverse(newestFirst);
            return newestFirst.subList(0, Math.min(3, newestFirst.size()));
        });
        when(routeService.createRoute(anyLong(), any())).thenReturn(new Route());
        ResponseComposer composer = mock(ResponseComposer.class);
        when(composer.askWhenForBreakfast()).thenCallRealMethod();
        when(composer.planCreated(any(), any())).thenReturn("plan");
        AreaResolver areas = mock(AreaResolver.class);
        when(areas.resolve(any(), anyString(), any(), any())).thenReturn(Optional.empty());

        service = new AssistantService(new RuleBasedIntentParser(EVENING), routeService,
                mock(RecommendationService.class), mock(WeatherService.class), composer, messages,
                AssistantConversationsTest.conversationRepository(), areas, EVENING);
    }

    @Test
    void asksNowOrAnotherDayThenPlansTheAnsweredDayWithTheFirstRequest() {
        AssistantReply question = service.handle(1L, new AssistantRequest(REQUEST, 40.8, 29.37, null));

        assertThat(question.reply()).startsWith("Kahvaltı saati geçti.").contains("şimdi için mi");
        verify(routeService, never()).createRoute(anyLong(), any());

        Long conversation = question.conversationId();
        service.handle(1L, new AssistantRequest("yarın sabah", 40.8, 29.37, null, conversation));

        ArgumentCaptor<RoutePlanRequest> plan = ArgumentCaptor.forClass(RoutePlanRequest.class);
        verify(routeService).createRoute(eq(1L), plan.capture());
        assertThat(plan.getValue().date()).isEqualTo(LocalDate.of(2026, 9, 30));
        // The first message's details are kept
        assertThat(plan.getValue().partySize()).isEqualTo(2);
        assertThat(plan.getValue().budget()).isEqualTo(700);
    }

    @Test
    void sayingNowPlansRightAway() {
        AssistantReply reply = service.handle(1L, new AssistantRequest("şimdi " + REQUEST, 40.8, 29.37, null));

        assertThat(reply.reply()).isEqualTo("plan");
        verify(routeService).createRoute(eq(1L), any());
    }
}

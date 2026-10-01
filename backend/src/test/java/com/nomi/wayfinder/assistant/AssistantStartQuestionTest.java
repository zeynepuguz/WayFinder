package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.dto.RouteDtos.StartMode;
import com.nomi.wayfinder.entity.AssistantMessage;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.StartKind;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.service.RouteStartService;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// A route request without a start: "Rotaya nereden başlayalım?", then the route starts at the named place
class AssistantStartQuestionTest {

    private static final Clock MORNING = Clock.fixed(Instant.parse("2026-10-02T06:00:00Z"), ZoneId.of("Europe/Istanbul"));

    private final RouteService routeService = mock(RouteService.class);
    private final RouteStartService startService = mock(RouteStartService.class);
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
            Collections.reverse(newestFirst);
            return newestFirst.subList(0, Math.min(((Pageable) inv.getArgument(1)).getPageSize(), newestFirst.size()));
        });
        when(routeService.createRoute(anyLong(), any())).thenReturn(new Route());
        ResponseComposer composer = mock(ResponseComposer.class);
        when(composer.askStart(any())).thenCallRealMethod();
        when(composer.planCreated(any(), any())).thenReturn("plan");
        AreaResolver areas = mock(AreaResolver.class);
        when(areas.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(startService.placeNamed(eq("Anıtkabir"), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new RouteStartService.Start(39.925, 32.837, StartKind.SIGHT, "Anıtkabir")));

        service = new AssistantService(new RuleBasedIntentParser(MORNING), routeService,
                mock(RecommendationService.class), mock(WeatherService.class), composer, messages,
                AssistantConversationsTest.conversationRepository(), areas, MORNING, null, null, startService);
    }

    @Test
    void asksWhereToStartThenStartsAtTheNamedPlace() {
        AssistantReply question = service.handle(1L, new AssistantRequest("2 kişiyiz, 5000 TL, tarih ve müze", 39.9, 32.8, null));
        assertThat(question.reply()).startsWith("Rotaya nereden başlayalım?");
        verify(routeService, never()).createRoute(anyLong(), any());

        service.handle(1L, new AssistantRequest("Anıtkabir'den başlayalım", 39.9, 32.8, null, question.conversationId()));

        ArgumentCaptor<RoutePlanRequest> plan = ArgumentCaptor.forClass(RoutePlanRequest.class);
        verify(routeService).createRoute(eq(1L), plan.capture());
        assertThat(plan.getValue().latitude()).isEqualTo(39.925);
        assertThat(plan.getValue().startLabel()).isEqualTo("Anıtkabir");
        assertThat(plan.getValue().startMode()).isEqualTo(StartMode.LOCATION);
        // The first message's details are kept
        assertThat(plan.getValue().partySize()).isEqualTo(2);
    }

    @Test
    void startNameDropsSuffixAndFillerWords() {
        assertThat(AssistantService.startName("Anıtkabir'den başlayalım")).isEqualTo("Anıtkabir");
        assertThat(AssistantService.startName("Kızılay’dan olsun lütfen")).isEqualTo("Kızılay");
        assertThat(AssistantService.startName("Kuğulu Park")).isEqualTo("Kuğulu Park");
    }
}

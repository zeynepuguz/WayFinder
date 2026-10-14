package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.area.NamedArea;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The bug of 2026-10-14: "İstanbul için bir rota öner" -> "İstanbul içinde rotaya nereden başlayalım?" ->
 * "Esenler otogar" got lunch places around the user in Kocaeli. The LLM read the same plan request as RECOMMEND the
 * second time; the answer to our own start question must finish the plan, in İstanbul, at the bus station.
 */
class AssistantRemembersThePlanTest {

    private static final Clock NOON = Clock.fixed(Instant.parse("2026-10-14T10:13:00Z"), ZoneId.of("Europe/Istanbul"));
    // The user is in Kocaeli
    private static final double USER_LAT = 40.80;
    private static final double USER_LON = 29.43;
    private static final NamedArea ISTANBUL = new NamedArea("İstanbul", NamedArea.Kind.CITY, 41.01, 28.97, null,
            "İstanbul", "istanbul", null);
    private static final NamedArea ESENLER = new NamedArea("Esenler", NamedArea.Kind.DISTRICT, 41.04, 28.88,
            "Esenler", "İstanbul", "istanbul", "esenler");

    private final RouteService routeService = mock(RouteService.class);
    private final RecommendationService recommendations = mock(RecommendationService.class);
    private final RouteStartService startService = mock(RouteStartService.class);
    private final List<AssistantMessage> saved = new ArrayList<>();
    private final AtomicInteger parses = new AtomicInteger();
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
        when(composer.askStart(any(), any())).thenCallRealMethod();
        when(composer.planCreated(any(), any())).thenReturn("plan");

        AreaResolver areas = mock(AreaResolver.class);
        when(areas.resolve(eq("İstanbul"), any(), any(), any())).thenReturn(Optional.of(ISTANBUL));
        when(areas.resolve(isNull(), eq("Esenler otogar"), anyDouble(), anyDouble())).thenReturn(Optional.of(ESENLER));
        when(startService.placeNamed(eq("Esenler otogar"), anyDouble(), anyDouble())).thenReturn(Optional.of(
                new RouteStartService.Start(41.0389, 28.8945, StartKind.SIGHT, "Sarı Fırın Otogar Esenler")));

        // Like the LLM that day: the same request is a plan the first time, a suggestion of places the second time
        IntentParser flaky = (message, context) -> new AssistantIntent(parses.getAndIncrement() == 0
                ? AssistantIntent.IntentType.PLAN_ROUTE : AssistantIntent.IntentType.RECOMMEND,
                null, List.of(), null, "ai", null, "İstanbul");

        service = new AssistantService(flaky, routeService, recommendations, mock(WeatherService.class), composer,
                messages, AssistantConversationsTest.conversationRepository(), areas, NOON, null, null, startService);
    }

    @Test
    void theAnswerToTheStartQuestionFinishesThePlanInTheNamedCity() {
        AssistantReply question = service.handle(1L, new AssistantRequest("İstanbul için bir rota öner", USER_LAT,
                USER_LON, null));
        assertThat(question.reply()).startsWith("İstanbul içinde rotaya nereden başlayalım?");

        service.handle(1L, new AssistantRequest("Esenler otogar", USER_LAT, USER_LON, null, question.conversationId()));

        verifyNoInteractions(recommendations);
        ArgumentCaptor<RoutePlanRequest> plan = ArgumentCaptor.forClass(RoutePlanRequest.class);
        verify(routeService).createRoute(eq(1L), plan.capture());
        // At the bus station in İstanbul, not around the user in Kocaeli
        assertThat(plan.getValue().latitude()).isEqualTo(41.0389);
        assertThat(plan.getValue().longitude()).isEqualTo(28.8945);
        assertThat(plan.getValue().startLabel()).isEqualTo("Sarı Fırın Otogar Esenler");
    }

    @Test
    void aDistrictAloneIsTheDistrictAPlaceInItIsThePlace() {
        assertThat(AssistantService.namesMoreThan("Esenler otogar", "Esenler")).isTrue();
        assertThat(AssistantService.namesMoreThan("Kadıköy", "Kadıköy")).isFalse();
        assertThat(AssistantService.namesMoreThan("Moda Sahili", "Moda")).isTrue();
    }
}

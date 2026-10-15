package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.NamedArea;
import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.PopularRouteService;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The chat of 2026-10-15 (the user in Kocaeli): "bir günlük rota" was planned in the Günlük neighbourhood, "of
 * ilçesinden" was not understood, and "trabzon of" showed the empty route of the chat instead of planning one in Of.
 */
class AssistantPlaceWordsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T13:00:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final String DAY = "Tarihi yerler ve müzeler ağırlıklı bir günlük rota planla";
    private static final NamedArea OF = new NamedArea("Of", NamedArea.Kind.DISTRICT, 40.95, 40.27, "Of", "Trabzon",
            "trabzon", "of");

    private final IntentParser parser = mock(IntentParser.class);
    private final RouteService routeService = mock(RouteService.class);
    private final AreaResolver areas = mock(AreaResolver.class);
    private final ResponseComposer composer = mock(ResponseComposer.class);
    private AssistantService service;

    @BeforeEach
    void setUp() {
        when(routeService.createRoute(anyLong(), any())).thenReturn(new Route());
        when(composer.planCreated(any(), any())).thenReturn("plan");
        when(composer.showRoute(any())).thenReturn("rota:");
        when(areas.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());
        service = new AssistantService(parser, routeService, mock(RecommendationService.class),
                mock(WeatherService.class), composer, mock(AssistantMessageRepository.class),
                AssistantConversationsTest.conversationRepository(), areas, CLOCK, mock(PopularRouteService.class),
                mock(CityService.class));
    }

    private static AssistantIntent ai(IntentType type, String area) {
        return new AssistantIntent(type, null, List.of(), null, "ai", null, area);
    }

    @Test
    void whenTheAiFindsNoPlaceAWordOfTheMessageIsNoPlaceEither() {
        when(parser.parse(eq(DAY), any())).thenReturn(ai(IntentType.PLAN_ROUTE, null));

        service.handle(1L, new AssistantRequest(DAY, 40.80, 29.43, null, null));

        // the message is not searched for place names ("günlük")
        verify(areas).resolve(isNull(), isNull(), eq(40.80), eq(29.43));
    }

    @Test
    void theRuleBasedParserStillHasTheMessageSearched() {
        when(parser.parse(eq(DAY), any())).thenReturn(new AssistantIntent(IntentType.PLAN_ROUTE, null, List.of(), null,
                "RULES", null, null));

        service.handle(1L, new AssistantRequest(DAY, 40.80, 29.43, null, null));

        verify(areas).resolve(isNull(), eq(DAY), eq(40.80), eq(29.43));
    }

    @Test
    void namingAPlaceInAChatWithAnEmptyRoutePlansThereInsteadOfShowingIt() {
        Route empty = new Route();
        ReflectionTestUtils.setField(empty, "id", 7L);
        when(routeService.getRouteEntity(1L, 7L)).thenReturn(empty);
        when(parser.parse(eq("trabzon of"), any())).thenReturn(ai(IntentType.SHOW_ROUTE, "trabzon"));
        when(areas.resolve(eq("trabzon"), eq("trabzon of"), any(), any())).thenReturn(Optional.of(OF));

        service.handle(1L, new AssistantRequest("trabzon of", 40.80, 29.43, 7L, null));

        verify(routeService).createRoute(eq(1L), argThat(r -> r.latitude() != 40.80));
        verify(composer, never()).showRoute(any());
    }

}

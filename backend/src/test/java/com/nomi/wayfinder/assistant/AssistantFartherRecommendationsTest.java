package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.dto.NearbyPlaceResponse;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import com.nomi.wayfinder.service.RecommendationService.TieredRecommendations;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// "Daha uygun ama sana yakın değil" is offered only when the user asks the assistant for suggestions
class AssistantFartherRecommendationsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneId.of("Europe/Istanbul"));

    private final IntentParser intentParser = mock(IntentParser.class);
    private final RecommendationService recommendationService = mock(RecommendationService.class);
    private AssistantService service;

    @BeforeEach
    void setUp() {
        RouteService routeService = mock(RouteService.class);
        when(routeService.findCurrentRoute(anyLong())).thenReturn(Optional.empty());
        service = new AssistantService(intentParser, routeService, recommendationService, mock(WeatherService.class),
                new ResponseComposer(), mock(AssistantMessageRepository.class), AssistantConversationsTest.conversationRepository(),
                mock(AreaResolver.class), CLOCK);
    }

    @Test
    void recommendReturnsFartherPlacesAsTheirOwnLowerRankedList() {
        Recommendation near = recommendation("Yakın Kafe", List.of("Başlangıç noktana 300 m (~6 dk yürüme)"), null);
        Recommendation far = recommendation("Uzak Kafe", List.of("1,8 km uzakta (yürüyerek ~32 dk)"), "Puanı daha yüksek (4.7)");
        when(intentParser.parse(anyString(), any()))
                .thenReturn(new AssistantIntent(IntentType.RECOMMEND, null, List.of(), StopType.COFFEE, "RULES"));
        when(recommendationService.recommendTiered(anyDouble(), anyDouble(), eq(StopType.COFFEE), eq(1L), eq(3), eq(2)))
                .thenReturn(new TieredRecommendations(List.of(near), List.of(far)));

        AssistantReply reply = service.handle(1L, new AssistantRequest("kahve öner", 40.99, 29.02, null));

        assertThat(reply.recommendations()).containsExactly(near);
        assertThat(reply.fartherRecommendations()).containsExactly(far);
        assertThat(reply.reply()).contains("1. Yakın Kafe").contains("Daha uygun ama sana yakın değil:\n• Uzak Kafe");
        assertThat(reply.reply().indexOf("Yakın Kafe")).isLessThan(reply.reply().indexOf("Uzak Kafe"));
    }

    @Test
    void otherIntentsHaveAnEmptyFartherList() {
        when(intentParser.parse(anyString(), any()))
                .thenReturn(new AssistantIntent(IntentType.UNKNOWN, null, List.of(), null, "RULES"));

        AssistantReply reply = service.handle(1L, new AssistantRequest("merhaba", 40.99, 29.02, null));

        assertThat(reply.recommendations()).isEmpty();
        assertThat(reply.fartherRecommendations()).isNotNull().isEmpty();
        verifyNoInteractions(recommendationService);
    }

    private static Recommendation recommendation(String name, List<String> reasons, String whyBetter) {
        NearbyPlaceResponse place = new NearbyPlaceResponse();
        place.setName(name);
        return new Recommendation(place, StopType.COFFEE, 80, reasons, whyBetter);
    }
}

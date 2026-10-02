package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.NamedArea;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.dto.RouteDtos.StartMode;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.PopularRouteService;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The message that failed: "bursaya 4 arkadaş ... ünlü bir rota ... her birimizin 1000'er TL" was planned at the
 * user's position in Kocaeli, for one person, without a budget.
 */
class AssistantCityTripTest {

    static final String MESSAGE = "bursaya 4 arkadaş gezmeye gideceğiz bize az yürüyeceğimiz şekilde ünlü bir rota "
            + "oluşturur musun her birimizin ayrı ayrı bütçesi 1000'er TL";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T07:00:00Z"), ZoneId.of("Europe/Istanbul"));
    // The user is in Çayırova (Kocaeli)
    private static final double GPS_LAT = 40.8161;
    private static final double GPS_LON = 29.3756;
    private static final NamedArea BURSA = new NamedArea("Bursa", NamedArea.Kind.CITY, 40.1826, 29.0665, null,
            "Bursa", "bursa", null);

    private final RouteService routeService = mock(RouteService.class);
    private final PopularRouteService popularRoutes = mock(PopularRouteService.class);
    private final ResponseComposer composer = mock(ResponseComposer.class);
    private AssistantService service;

    @BeforeEach
    void setUp() {
        AreaResolver areaResolver = mock(AreaResolver.class);
        when(areaResolver.resolve(any(), anyString(), any(), any())).thenReturn(Optional.of(BURSA));
        when(routeService.createRoute(anyLong(), any())).thenReturn(new Route());
        when(composer.planCreated(any(), any())).thenReturn("plan");
        when(composer.popularPlanCreated(any(), any())).thenReturn("popular");
        when(composer.noPopularRoute(any())).thenReturn("none. ");

        service = new AssistantService(new RuleBasedIntentParser(CLOCK), routeService, mock(RecommendationService.class),
                mock(WeatherService.class), composer, mock(AssistantMessageRepository.class),
                AssistantConversationsTest.conversationRepository(), areaResolver, CLOCK, popularRoutes,
                mock(CityService.class));
    }

    @Test
    void famousSightsOfTheNamedCityForTheWholeGroup() {
        RouteResponse popular = mock(RouteResponse.class);
        when(popularRoutes.startBest(eq(1L), eq("bursa"), isNull(), any(), eq(4), eq(4000), eq(true)))
                .thenReturn(Optional.of(popular));

        AssistantReply reply = service.handle(1L, new AssistantRequest(MESSAGE, GPS_LAT, GPS_LON, null));

        assertThat(reply.reply()).isEqualTo("Popular");
        assertThat(reply.route()).isSameAs(popular);
        verify(routeService, never()).createRoute(anyLong(), any());
    }

    @Test
    void withoutAPopularRouteTheDayStillStartsInTheNamedCity() {
        when(popularRoutes.startBest(anyLong(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        AssistantReply reply = service.handle(1L, new AssistantRequest(MESSAGE, GPS_LAT, GPS_LON, null));

        ArgumentCaptor<RoutePlanRequest> request = ArgumentCaptor.forClass(RoutePlanRequest.class);
        verify(routeService).createRoute(eq(1L), request.capture());
        assertThat(request.getValue().startMode()).isEqualTo(StartMode.AREA);
        assertThat(request.getValue().city()).isEqualTo("bursa");
        assertThat(request.getValue().latitude()).isEqualTo(40.1826);
        assertThat(request.getValue().partySize()).isEqualTo(4);
        assertThat(request.getValue().budget()).isEqualTo(4000);
        assertThat(reply.reply()).startsWith("None. ");
    }
}

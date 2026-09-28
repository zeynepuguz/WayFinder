package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaMatcher;
import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.area.NamedArea;
import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.UserPreferences;
import com.nomi.wayfinder.planning.PlaceScorer;
import com.nomi.wayfinder.planning.RoutePlanner;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.repository.RouteRepository;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RouteMapper;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.service.UserService;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static com.nomi.wayfinder.TestPlaces.openEveryDay;
import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The message that failed in production: the route was planned for today, from the user's GPS position (far
 * from Üsküdar, almost no places) and had one stop. Real parser, area matching, RouteService and RoutePlanner;
 * only the database and the weather API are faked.
 */
class AssistantAreaDateRouteTest {

    static final String MESSAGE = "ben yarın 2 kişi 700 tl ile üsküdarda gezeceğiz saat 13.00'da buluşacağız "
            + "bize rota önerir misin";

    // Sunday 27 September 2026, 20:00 in Istanbul
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T17:00:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final double USKUDAR_LAT = 41.0260;
    private static final double USKUDAR_LON = 29.0160;
    // Somewhere on the European side with nothing around
    private static final double GPS_LAT = 41.0930;
    private static final double GPS_LON = 28.8020;

    private final Map<Long, Place> places = new LinkedHashMap<>();
    private final WeatherService weatherService = mock(WeatherService.class);
    private AssistantService service;

    @BeforeEach
    void setUp() {
        // Affordable for two with 700 TL (lunch, coffee, dessert, dinner = 2 x 340 TL)
        add(place(1, "Üsküdar Lokantası", PlaceCategory.RESTAURANT, true, 4.4, 110), 41.0265, 29.0170);
        add(place(2, "Kuzguncuk Kahvecisi", PlaceCategory.CAFE, true, 4.5, 50), 41.0275, 29.0185);
        add(place(3, "Şemsi Paşa Camii", PlaceCategory.ATTRACTION, false, 4.7, 0, "history"), 41.0272, 29.0120);
        add(place(4, "Üsküdar Sahili", PlaceCategory.PARK, false, 4.6, 0, "sea"), 41.0250, 29.0110);
        add(place(5, "Tatlıcı", PlaceCategory.DESSERT, true, 4.3, 60), 41.0245, 29.0180);
        add(place(6, "Akşam Balıkçısı", PlaceCategory.RESTAURANT, true, 4.2, 120), 41.0240, 29.0150);
        add(place(7, "Mihrimah Sultan Müzesi", PlaceCategory.MUSEUM, true, 4.5, 0, "history"), 41.0268, 29.0140);

        PlaceRepository repository = mock(PlaceRepository.class);
        when(repository.findCandidates(anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(), anyInt()))
                .thenAnswer(inv -> {
                    double lat = inv.getArgument(0);
                    double lon = inv.getArgument(1);
                    double radius = inv.getArgument(2);
                    Collection<String> categories = inv.getArgument(3);
                    List<PlaceDistance> result = new ArrayList<>();
                    for (Place p : places.values()) {
                        double d = AreaMatcherDistance.meters(lat, lon, p.getLatitude(), p.getLongitude());
                        if (d <= radius && categories.contains(p.getCategory().name())) {
                            result.add(distance(p.getId(), d));
                        }
                    }
                    result.sort(Comparator.comparing(PlaceDistance::getDistanceMeters));
                    return result;
                });
        when(repository.findByIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(places::get).toList();
        });
        when(weatherService.getForecast(anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());

        RouteRepository routeRepository = mock(RouteRepository.class);
        when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
        UserService userService = mock(UserService.class);
        when(userService.getPreferences(anyLong())).thenReturn(new UserPreferences(1L));

        RouteService routeService = new RouteService(routeRepository,
                new RoutePlanner(repository, new PlaceScorer(), weatherService), userService, new RouteMapper(), CLOCK);

        AreaMatcher matcher = new AreaMatcher(List.of(
                new NamedArea("Üsküdar", NamedArea.Kind.DISTRICT, USKUDAR_LAT, USKUDAR_LON, "Üsküdar"),
                new NamedArea("Kadıköy", NamedArea.Kind.DISTRICT, 40.9900, 29.0280, "Kadıköy"),
                new NamedArea("Başakşehir", NamedArea.Kind.DISTRICT, 41.0930, 28.8020, "Başakşehir")));
        AreaResolver areaResolver = mock(AreaResolver.class);
        when(areaResolver.resolve(any(), anyString(), any(), any())).thenAnswer(inv -> {
            Optional<NamedArea> fromAi = matcher.find(inv.getArgument(0));
            return fromAi.isPresent() ? fromAi : matcher.find(inv.<String>getArgument(1));
        });

        service = new AssistantService(new RuleBasedIntentParser(CLOCK), routeService, mock(RecommendationService.class),
                weatherService, new ResponseComposer(), mock(AssistantMessageRepository.class),
                AssistantConversationsTest.conversationRepository(), areaResolver, CLOCK);
    }

    @Test
    void plansTomorrowInUskudarFromOnePmForTwoWith700Tl() {
        AssistantReply reply = service.handle(1L, new AssistantRequest(MESSAGE, GPS_LAT, GPS_LON, null));

        assertThat(reply.intent().type()).isEqualTo(IntentType.PLAN_ROUTE);
        assertThat(reply.intent().date()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(reply.intent().area()).isEqualTo("Üsküdar");

        var route = reply.route();
        assertThat(route.date()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(route.title()).isEqualTo("28 Eylül Pazartesi Rotası");
        assertThat(route.startTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(route.partySize()).isEqualTo(2);
        assertThat(route.budget()).isEqualTo(700);
        assertThat(route.startLatitude()).isEqualTo(USKUDAR_LAT);
        assertThat(route.startLongitude()).isEqualTo(USKUDAR_LON);
        assertThat(route.stops()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(route.stops().getFirst().plannedStart()).isAfterOrEqualTo(LocalTime.of(13, 0));
        assertThat(route.totalEstimatedCost()).isLessThanOrEqualTo(700);

        assertThat(reply.reply()).startsWith("Üsküdar'dan başlayan bir rota hazırladım. 28 Eylül Pazartesi Rotası hazır!");
        // The forecast is asked for the planned day, not today
        verify(weatherService, atLeastOnce()).getForecast(anyDouble(), anyDouble(), eq(LocalDate.of(2026, 9, 28)));
        verify(weatherService, never()).getForecast(anyDouble(), anyDouble(), eq(LocalDate.of(2026, 9, 27)));
    }

    @Test
    void withoutAKnownAreaTheRouteStillStartsAtTheUsersPosition() {
        AssistantReply reply = service.handle(1L, new AssistantRequest(
                "yarın 2 kişi 700 tl ile gezeceğiz, rota önerir misin", 41.0262, 29.0165, null));

        assertThat(reply.route().startLatitude()).isEqualTo(41.0262);
        assertThat(reply.reply()).doesNotContain("başlayan bir rota");
        assertThat(reply.route().date()).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    private void add(Place place, double lat, double lon) {
        place.setCoordinates(lat, lon);
        openEveryDay(place, "08:00", "23:30");
        places.put(place.getId(), place);
    }

    private static PlaceDistance distance(long id, double meters) {
        return new PlaceDistance() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Double getDistanceMeters() {
                return meters;
            }
        };
    }

    // Straight-line meters, like PostGIS on geography
    static final class AreaMatcherDistance {
        static double meters(double lat1, double lon1, double lat2, double lon2) {
            double dLat = Math.toRadians(lat2 - lat1);
            double dLon = Math.toRadians(lon2 - lon1);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
        }
    }
}

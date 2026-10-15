package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.NamedArea;
import com.nomi.wayfinder.assistant.AssistantIntent.PlanParams;
import com.nomi.wayfinder.assistant.AssistantIntent.RouteEdit;
import com.nomi.wayfinder.assistant.IntentParser.IntentContext;
import com.nomi.wayfinder.assistant.IntentParser.StopRef;
import com.nomi.wayfinder.dto.RouteDtos.ReplanRequest;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.dto.RouteDtos.StartMode;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.planning.ReplanType;
import com.nomi.wayfinder.repository.AssistantConversationRepository;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.PopularRouteService;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import com.nomi.wayfinder.service.RecommendationService.TieredRecommendations;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.WeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * The assistant flow:
 * message -> intent (AI or rules) -> backend executes it with real data -> answer built from the results.
 * <p>
 * Every message belongs to one chat (AssistantConversation). A message without a chat id starts a new chat; it never
 * continues an older one. Route changes ("çok yorulduk", "yağmur başladı", "burayı çıkar") apply only to the route
 * that chat planned, never to another chat's route.
 */
@Service
public class AssistantService {

    static final int MAX_FARTHER = 2;
    static final int TITLE_LENGTH = 40;

    private final IntentParser intentParser;
    private final RouteService routeService;
    private final RecommendationService recommendationService;
    private final WeatherService weatherService;
    private final ResponseComposer composer;
    private final AssistantMessageRepository messageRepository;
    private final AssistantConversationRepository conversationRepository;
    private final AreaResolver areaResolver;
    private final Clock clock;
    // "ünlü bir rota" and the user's city; null in tests that do not need them
    private final PopularRouteService popularRoutes;
    private final CityService cityService;

    public AssistantService(
            IntentParser intentParser,
            RouteService routeService,
            RecommendationService recommendationService,
            WeatherService weatherService,
            ResponseComposer composer,
            AssistantMessageRepository messageRepository,
            AssistantConversationRepository conversationRepository,
            AreaResolver areaResolver,
            Clock clock
    ) {
        this(intentParser, routeService, recommendationService, weatherService, composer, messageRepository,
                conversationRepository, areaResolver, clock, null, null);
    }

    @Autowired
    public AssistantService(
            IntentParser intentParser,
            RouteService routeService,
            RecommendationService recommendationService,
            WeatherService weatherService,
            ResponseComposer composer,
            AssistantMessageRepository messageRepository,
            AssistantConversationRepository conversationRepository,
            AreaResolver areaResolver,
            Clock clock,
            PopularRouteService popularRoutes,
            CityService cityService
    ) {
        this.popularRoutes = popularRoutes;
        this.cityService = cityService;
        this.intentParser = intentParser;
        this.routeService = routeService;
        this.recommendationService = recommendationService;
        this.weatherService = weatherService;
        this.composer = composer;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.areaResolver = areaResolver;
        this.clock = clock;
    }

    @Transactional
    public AssistantReply handle(Long userId, AssistantRequest request) {
        // From a route page (routeId, no chat): the chat about that route, so its history stays in one place
        AssistantConversation conversation = request.conversationId() != null
                ? AssistantConversationService.owned(conversationRepository, userId, request.conversationId())
                : request.routeId() != null
                ? conversationRepository.findFirstByUserIdAndRouteIdOrderByLastMessageAtDescIdDesc(userId, request.routeId())
                        .orElseGet(() -> conversationRepository.save(new AssistantConversation(userId)))
                : conversationRepository.save(new AssistantConversation(userId));
        Route route = conversationRoute(userId, conversation, request.routeId());

        messageRepository.save(new AssistantMessage(userId, conversation.getId(), route == null ? null : route.getId(),
                MessageRole.USER, request.message()));

        AssistantIntent intent = intentParser.parse(request.message(), context(route));

        // "trabzon of" in a chat that has a route came back as SHOW_ROUTE: naming a place asks for a route there, and an
        // empty route (nothing found last time) is nothing to show
        if (intent.type() == AssistantIntent.IntentType.SHOW_ROUTE
                && (route == null || route.getStops().isEmpty() || (intent.area() != null && !intent.area().isBlank()))) {
            intent = new AssistantIntent(AssistantIntent.IntentType.PLAN_ROUTE, intent.plan(), List.of(), null,
                    intent.source(), intent.date(), intent.area());
        }

        AssistantReply reply = switch (intent.type()) {
            case PLAN_ROUTE -> planRoute(userId, request, intent);
            // Only this chat's route; a past day's route is read-only
            case REPLAN -> route == null ? text(intent, composer.noRoute())
                    : routeService.isPast(route) ? text(intent, RouteService.pastRouteMessage())
                    : replan(route, request, intent);
            case RECOMMEND -> recommend(userId, request, intent);
            case WEATHER -> weather(request, intent.withDateAndArea(day(intent, request.message()), intent.area()));
            case SHOW_ROUTE -> route == null ? text(intent, composer.noRoute())
                    : withRoute(intent, composer.showRoute(routeService.toResponse(route)), routeService.toResponse(route), List.of());
            case UNKNOWN -> text(intent, composer.help());
        };

        // A route planned in this chat becomes the chat's route (a later plan in the same chat replaces it)
        if (intent.type() == AssistantIntent.IntentType.PLAN_ROUTE && reply.route() != null) {
            conversation.setRouteId(reply.route().id());
        }
        Long replyRouteId = reply.route() != null ? reply.route().id() : route == null ? null : route.getId();
        messageRepository.save(new AssistantMessage(userId, conversation.getId(), replyRouteId,
                MessageRole.ASSISTANT, reply.reply()));

        if (conversation.getTitle() == null) {
            conversation.setTitle(titleFrom(request.message()));
        }
        conversation.setLastMessageAt(clock.instant());
        conversationRepository.save(conversation);

        return reply.withConversationId(conversation.getId());
    }

    /**
     * The only route this chat's messages may change: the one it planned. An explicit routeId (e.g. "ask about this
     * route") links a route to a chat that has none yet; it never replaces the chat's own route.
     */
    private Route conversationRoute(Long userId, AssistantConversation conversation, Long requestedRouteId) {
        if (conversation.getRouteId() != null) {
            return routeService.findRouteEntity(userId, conversation.getRouteId()).orElse(null);
        }
        if (requestedRouteId != null) {
            Route route = routeService.getRouteEntity(userId, requestedRouteId);
            conversation.setRouteId(route.getId());
            return route;
        }
        return null;
    }

    // "2 kişiyiz, 700 TL bütçemiz var, kahvaltı ve kahve istiyoruz" -> "2 kişiyiz, 700 TL bütçemiz var,…"
    static String titleFrom(String message) {
        return AssistantConversationService.shorten(message, TITLE_LENGTH);
    }

    // ============ intent handlers ============

    // Breakfast asked for without a day once breakfast time is over: it is planned for tomorrow morning
    static final LocalTime BREAKFAST_OVER = LocalTime.of(11, 30);

    /**
     * Plans right away, without questions: from the place the message names (a district / neighbourhood: there; a city
     * alone: its best-known start, like "Yeni rota > şehir"), else from where the user is. The user changes anything
     * afterwards in the same chat ("Kızılay'dan başlasın", "yarın olsun").
     */
    private AssistantReply planRoute(Long userId, AssistantRequest request, AssistantIntent intent) {
        PlanParams p = intent.plan() != null ? intent.plan()
                : new PlanParams(null, null, null, List.of(), List.of(), null);

        // The AI service read the message: when it found no place, a word of it is no place either ("bir günlük rota"
        // is no trip to the Günlük neighbourhood). The rule-based parser has no area field: the message is searched
        boolean aiSaidNoPlace = "ai".equals(intent.source()) && (intent.area() == null || intent.area().isBlank());
        Optional<NamedArea> area = areaResolver.resolve(intent.area(), aiSaidNoPlace ? null : request.message(),
                request.latitude(), request.longitude());

        LocalDate date = day(intent, request.message());
        String dayNote = "";
        if (date == null && breakfastTomorrow(request, p)) {
            date = LocalDate.now(clock).plusDays(1);
            dayNote = composer.breakfastTomorrow();
        }
        double latitude = area.map(NamedArea::latitude).orElse(request.latitude());
        double longitude = area.map(NamedArea::longitude).orElse(request.longitude());
        String areaName = area.map(NamedArea::name).orElse(null);
        AssistantIntent resolved = intent.withDateAndArea(date, area.map(NamedArea::name).orElse(intent.area()));

        // A named city / district starts like "Rotalarım > Yeni rota > şehir / ilçe": at its best-known sight with
        // cafés around, else its centre. A neighbourhood (or nothing named) starts at its point / the user's position
        String citySlug = area.filter(a -> a.kind() != NamedArea.Kind.AREA).map(NamedArea::citySlug).orElse(null);
        String districtSlug = area.filter(a -> a.kind() == NamedArea.Kind.DISTRICT)
                .map(NamedArea::districtSlug).orElse(null);

        // "ünlü bir rota": one of the area's popular routes (famous sights by Wikipedia popularity)
        String popularNote = "";
        if (p.wantsPopular() && popularRoutes != null) {
            String city = citySlug != null ? citySlug : currentCitySlug(request);
            Optional<RouteResponse> popular = popularRoutes.startBest(userId, city, districtSlug, date,
                    p.partySize(), p.budget(), p.walkingTolerance() == WalkingTolerance.LOW);
            if (popular.isPresent()) {
                RouteResponse kept = routeService.keepInChatOnly(userId, popular.get().id());
                return withRoute(resolved, composer.popularPlanCreated(kept, areaName), kept, List.of());
            }
            popularNote = composer.noPopularRoute(areaName);
        }

        boolean areaStart = citySlug != null;
        Route route = routeService.createRoute(userId, new RoutePlanRequest(
                latitude, longitude, date, p.startTime(), null,
                p.partySize(), p.budget(), p.walkingTolerance(), p.stops(), p.interests(), null,
                areaStart ? citySlug : null, areaStart ? districtSlug : null,
                areaStart ? StartMode.AREA : null, null));
        // Offered when the user leaves the chat: "Rotalarım'a eklensin mi?" (not in "Rotalarım" until then)
        route.setListed(false);

        RouteResponse response = routeService.toResponse(route);
        return withRoute(resolved, dayNote + popularNote + composer.planCreated(response, areaName), response,
                List.of());
    }

    // Breakfast, no day named, breakfast time over, and not "şimdi" / "bugün": tomorrow morning
    private boolean breakfastTomorrow(AssistantRequest request, PlanParams p) {
        if (p.stops() == null || !p.stops().contains(StopType.BREAKFAST) || LocalTime.now(clock).isBefore(BREAKFAST_OVER)) {
            return false;
        }
        String text = request.message().toLowerCase(Texts.TURKISH);
        return !(text.contains("şimdi") || text.contains("hemen") || text.contains("bugün")
                || text.matches("(?s).*\\b(now|today|right away)\\b.*"));
    }

    // The city (slug) the user is in, for "ünlü bir rota" without a place name
    private String currentCitySlug(AssistantRequest request) {
        if (cityService == null || request.latitude() == null || request.longitude() == null) {
            return null;
        }
        return cityService.findCityAt(request.latitude(), request.longitude()).map(CityService.City::slug).orElse(null);
    }

    /**
     * The day the user means: the parser's date (AI or rules), else what the rules find in the message
     * (the AI may leave it out). Past days are ignored (= today) instead of failing the request.
     */
    private LocalDate day(AssistantIntent intent, String message) {
        LocalDate today = LocalDate.now(clock);
        LocalDate date = intent.date() != null ? intent.date() : IntentDates.parse(message, today);
        return date == null || date.isBefore(today) ? null : date;
    }

    private AssistantReply replan(Route route, AssistantRequest request, AssistantIntent intent) {
        List<String> changes = new ArrayList<>();

        for (RouteEdit edit : intent.edits()) {
            Long stopId = null;
            if (edit.type() == ReplanType.REMOVE_STOP || edit.type() == ReplanType.REPLACE_STOP) {
                Optional<RouteStop> target = resolveTarget(route, edit);
                if (target.isEmpty()) {
                    changes.add(Texts.t("Hangi durağı kastettiğini anlayamadım; mekan adını yazar mısın?",
                            "I could not tell which stop you meant; could you write the name of the place?"));
                    continue;
                }
                stopId = target.get().getId();
            }

            try {
                changes.addAll(routeService.replanRoute(route, new ReplanRequest(
                        edit.type(), request.latitude(), request.longitude(), stopId, edit.stopType(), edit.interest())));
            } catch (BusinessException e) {
                changes.add(Texts.t("Bu değişikliği yapamadım: ", "I could not make this change: ") + e.getMessage());
            }
        }

        RouteResponse response = routeService.toResponse(route);
        return withRoute(intent, composer.routeChanged(response, changes), response, changes);
    }

    private AssistantReply recommend(Long userId, AssistantRequest request, AssistantIntent intent) {
        StopType type = intent.recommendType() != null ? intent.recommendType()
                : RecommendationService.suggestedTypeAt(LocalTime.now(clock));

        // Nearby first; up to MAX_FARTHER places that fit better but are farther come after them as lower-ranked
        // extra options (own sub-section of the text and their own list for the app's cards)
        TieredRecommendations tiered = recommendationService.recommendTiered(
                request.latitude(), request.longitude(), type, userId, 3, MAX_FARTHER);

        return new AssistantReply(composer.recommendations(tiered.nearby(), tiered.farther(), type.getLabel()),
                intent, null, tiered.nearby(), tiered.farther(), List.of());
    }

    private AssistantReply weather(AssistantRequest request, AssistantIntent intent) {
        LocalDate today = LocalDate.now(clock);
        if (intent.date() != null && !intent.date().equals(today)) {
            return text(intent, dayWeather(request, intent.date()));
        }
        String answer = weatherService.getForecast(request.latitude(), request.longitude(), LocalDate.now(clock))
                .map(f -> {
                    LocalTime now = LocalTime.now(clock);
                    LocalTime until = now.isBefore(LocalTime.of(21, 0)) ? LocalTime.of(22, 0) : LocalTime.of(23, 59);
                    var current = f.current() != null ? f.current() : f.at(now);
                    return String.format(Texts.locale(), Texts.t("Şu an %.0f°C ve %s. %s", "It is %.0f°C and %s right now. %s"),
                            current.temperature(), current.condition().getLabel(), weatherService.advice(f, now, until));
                })
                .orElse(Texts.t("Şu an hava durumu bilgisine ulaşamıyorum.", "I cannot get the weather information right now."));
        return text(intent, answer);
    }

    // "yarın hava nasıl": the forecast for the whole day (09:00-22:00)
    private String dayWeather(AssistantRequest request, LocalDate date) {
        String day = RouteService.dayName(date);
        return weatherService.getForecast(request.latitude(), request.longitude(), date)
                .map(f -> {
                    var summary = f.summarize(LocalTime.of(9, 0), LocalTime.of(22, 0));
                    return String.format(Texts.locale(), Texts.t("%s: %s, en yüksek %.0f°C. %s", "%s: %s, up to %.0f°C. %s"),
                            day, Texts.lower(summary.condition().getLabel()), summary.maxTemperature(),
                            weatherService.advice(summary));
                })
                .orElse(Texts.t(day + " için hava durumu tahminine ulaşamıyorum (en fazla ~15 gün sonrası için tahmin var).",
                        "I cannot get a forecast for " + day + " (forecasts reach about 15 days ahead)."));
    }

    // ============ helpers ============

    // Which stop does "burayı" / "Çiya'yı" / "akşam yemeğini" mean?
    private static Optional<RouteStop> resolveTarget(Route route, RouteEdit edit) {
        List<RouteStop> remaining = route.getStops().stream()
                .filter(s -> s.getStatus() == StopStatus.PLANNED).toList();

        if (edit.targetText() != null) {
            String text = edit.targetText().toLowerCase(Texts.TURKISH);
            for (RouteStop stop : remaining) {
                boolean nameMatch = Arrays.stream(stop.getPlace().getName().toLowerCase(Texts.TURKISH).split("\\s+"))
                        .map(w -> w.replaceAll("[^\\p{L}]", ""))
                        .filter(w -> w.length() >= 4)
                        .anyMatch(text::contains);
                if (nameMatch) {
                    return Optional.of(stop);
                }
            }
        }
        if (edit.targetStopType() != null) {
            Optional<RouteStop> byType = remaining.stream()
                    .filter(s -> s.getStopType() == edit.targetStopType()).findFirst();
            if (byType.isPresent()) {
                return byType;
            }
        }
        if (edit.targetIsCurrent() && !remaining.isEmpty()) {
            return Optional.of(remaining.getFirst());
        }
        return Optional.empty();
    }

    private static IntentContext context(Route route) {
        if (route == null) {
            return new IntentContext(false, List.of());
        }
        List<StopRef> stops = route.getStops().stream()
                .filter(s -> s.getStatus() == StopStatus.PLANNED)
                .map(s -> new StopRef(s.getId(), s.getStopType(), s.getPlace().getName()))
                .toList();
        return new IntentContext(true, stops);
    }

    private static AssistantReply text(AssistantIntent intent, String reply) {
        return new AssistantReply(reply, intent, null, List.of(), List.of(), List.of());
    }

    private static AssistantReply withRoute(AssistantIntent intent, String reply, RouteResponse route, List<String> changes) {
        return new AssistantReply(reply, intent, route, List.of(), List.of(), changes);
    }

    // ============ DTOs ============

    /**
     * @param conversationId the chat to continue; null starts a new chat
     * @param routeId        optional: links this route to a chat that has none yet
     */
    public record AssistantRequest(String message, Double latitude, Double longitude, Long routeId, Long conversationId) {

        public AssistantRequest(String message, Double latitude, Double longitude, Long routeId) {
            this(message, latitude, longitude, routeId, null);
        }
    }

    public record AssistantReply(
            String reply,
            AssistantIntent intent,
            RouteResponse route,
            List<Recommendation> recommendations,
            // "Daha uygun ama sana yakın değil": RECOMMEND only (max 2, otherwise empty), ranked below
            // recommendations. Each starts its reasons with a distance line and has whyBetter
            List<Recommendation> fartherRecommendations,
            List<String> changes,
            // The chat this message was added to (a new one when the request had none)
            Long conversationId
    ) {

        // Writing rules: every sentence of an answer starts with a capital letter
        public AssistantReply {
            reply = com.nomi.wayfinder.i18n.Sentences.capitalize(reply);
        }

        public AssistantReply(String reply, AssistantIntent intent, RouteResponse route, List<Recommendation> recommendations,
                              List<Recommendation> fartherRecommendations, List<String> changes) {
            this(reply, intent, route, recommendations, fartherRecommendations, changes, null);
        }

        AssistantReply withConversationId(Long id) {
            return new AssistantReply(reply, intent, route, recommendations, fartherRecommendations, changes, id);
        }
    }
}

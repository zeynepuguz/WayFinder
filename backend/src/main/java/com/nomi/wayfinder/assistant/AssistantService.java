package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.area.NamedArea;
import com.nomi.wayfinder.assistant.AssistantIntent.PlanParams;
import com.nomi.wayfinder.assistant.AssistantIntent.RouteEdit;
import com.nomi.wayfinder.assistant.IntentParser.IntentContext;
import com.nomi.wayfinder.assistant.IntentParser.StopRef;
import com.nomi.wayfinder.dto.RouteDtos.ReplanRequest;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.planning.ReplanType;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import com.nomi.wayfinder.service.RecommendationService.TieredRecommendations;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.weather.WeatherService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * The assistant flow:
 * message -> intent (AI or rules) -> backend executes it with real data -> answer built from the results.
 */
@Service
public class AssistantService {

    private static final Locale TR = Locale.forLanguageTag("tr-TR");
    static final int MAX_FARTHER = 2;

    private final IntentParser intentParser;
    private final RouteService routeService;
    private final RecommendationService recommendationService;
    private final WeatherService weatherService;
    private final ResponseComposer composer;
    private final AssistantMessageRepository messageRepository;
    private final AreaResolver areaResolver;
    private final Clock clock;

    public AssistantService(
            IntentParser intentParser,
            RouteService routeService,
            RecommendationService recommendationService,
            WeatherService weatherService,
            ResponseComposer composer,
            AssistantMessageRepository messageRepository,
            AreaResolver areaResolver,
            Clock clock
    ) {
        this.intentParser = intentParser;
        this.routeService = routeService;
        this.recommendationService = recommendationService;
        this.weatherService = weatherService;
        this.composer = composer;
        this.messageRepository = messageRepository;
        this.areaResolver = areaResolver;
        this.clock = clock;
    }

    @Transactional
    public AssistantReply handle(Long userId, AssistantRequest request) {
        Route route = request.routeId() != null
                ? routeService.getRouteEntity(userId, request.routeId())
                : routeService.findCurrentRoute(userId).orElse(null);

        messageRepository.save(new AssistantMessage(userId, route == null ? null : route.getId(),
                MessageRole.USER, request.message()));

        AssistantIntent intent = intentParser.parse(request.message(), context(route));

        AssistantReply reply = switch (intent.type()) {
            case PLAN_ROUTE -> planRoute(userId, request, intent);
            case REPLAN -> route == null ? text(intent, composer.noRoute()) : replan(route, request, intent);
            case RECOMMEND -> recommend(userId, request, intent);
            case WEATHER -> weather(request, intent.withDateAndArea(day(intent, request.message()), intent.area()));
            case SHOW_ROUTE -> route == null ? text(intent, composer.noRoute())
                    : withRoute(intent, composer.showRoute(routeService.toResponse(route)), routeService.toResponse(route), List.of());
            case UNKNOWN -> text(intent, composer.help());
        };

        Long replyRouteId = reply.route() != null ? reply.route().id() : route == null ? null : route.getId();
        messageRepository.save(new AssistantMessage(userId, replyRouteId, MessageRole.ASSISTANT, reply.reply()));

        return reply;
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> history(Long userId, int limit) {
        List<AssistantMessage> messages = new ArrayList<>(
                messageRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit)));
        Collections.reverse(messages);
        return messages.stream()
                .map(m -> new MessageResponse(m.getId(), m.getRole(), m.getContent(), m.getRouteId(), m.getCreatedAt()))
                .toList();
    }

    // ============ intent handlers ============

    private AssistantReply planRoute(Long userId, AssistantRequest request, AssistantIntent intent) {
        PlanParams p = intent.plan() != null ? intent.plan()
                : new PlanParams(null, null, null, List.of(), List.of(), null);

        // "üsküdarda gezeceğiz" / "Ankara'da": start there instead of at the user's GPS position (unknown names change
        // nothing). A name used in several cities means the one the user is in, unless the message names another city
        Optional<NamedArea> area = areaResolver.resolve(intent.area(), request.message(),
                request.latitude(), request.longitude());
        double latitude = area.map(NamedArea::latitude).orElse(request.latitude());
        double longitude = area.map(NamedArea::longitude).orElse(request.longitude());
        LocalDate date = day(intent, request.message());

        Route route = routeService.createRoute(userId, new RoutePlanRequest(
                latitude, longitude, date, p.startTime(), null,
                p.partySize(), p.budget(), p.walkingTolerance(), p.stops(), p.interests(), null));

        RouteResponse response = routeService.toResponse(route);
        AssistantIntent resolved = intent.withDateAndArea(date, area.map(NamedArea::name).orElse(intent.area()));
        return withRoute(resolved, composer.planCreated(response, area.map(NamedArea::name).orElse(null)),
                response, List.of());
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
            String text = edit.targetText().toLowerCase(TR);
            for (RouteStop stop : remaining) {
                boolean nameMatch = Arrays.stream(stop.getPlace().getName().toLowerCase(TR).split("\\s+"))
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

    public record AssistantRequest(String message, Double latitude, Double longitude, Long routeId) {
    }

    public record AssistantReply(
            String reply,
            AssistantIntent intent,
            RouteResponse route,
            List<Recommendation> recommendations,
            // "Daha uygun ama sana yakın değil": RECOMMEND only (max 2, otherwise empty), ranked below
            // recommendations. Each starts its reasons with a distance line and has whyBetter
            List<Recommendation> fartherRecommendations,
            List<String> changes
    ) {
    }

    public record MessageResponse(Long id, MessageRole role, String content, Long routeId, java.time.Instant createdAt) {
    }
}

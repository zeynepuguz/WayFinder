package com.nomi.wayfinder.assistant;

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

    private final IntentParser intentParser;
    private final RouteService routeService;
    private final RecommendationService recommendationService;
    private final WeatherService weatherService;
    private final ResponseComposer composer;
    private final AssistantMessageRepository messageRepository;
    private final Clock clock;

    public AssistantService(
            IntentParser intentParser,
            RouteService routeService,
            RecommendationService recommendationService,
            WeatherService weatherService,
            ResponseComposer composer,
            AssistantMessageRepository messageRepository,
            Clock clock
    ) {
        this.intentParser = intentParser;
        this.routeService = routeService;
        this.recommendationService = recommendationService;
        this.weatherService = weatherService;
        this.composer = composer;
        this.messageRepository = messageRepository;
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
            case WEATHER -> weather(request, intent);
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

        Route route = routeService.createRoute(userId, new RoutePlanRequest(
                request.latitude(), request.longitude(), null, p.startTime(), null,
                p.partySize(), p.budget(), p.walkingTolerance(), p.stops(), p.interests(), null));

        RouteResponse response = routeService.toResponse(route);
        return withRoute(intent, composer.planCreated(response), response, List.of());
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

        List<Recommendation> recommendations = recommendationService.recommend(
                request.latitude(), request.longitude(), type, userId, 3);

        return new AssistantReply(composer.recommendations(recommendations, type.getLabel()),
                intent, null, recommendations, List.of());
    }

    private AssistantReply weather(AssistantRequest request, AssistantIntent intent) {
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
        return new AssistantReply(reply, intent, null, List.of(), List.of());
    }

    private static AssistantReply withRoute(AssistantIntent intent, String reply, RouteResponse route, List<String> changes) {
        return new AssistantReply(reply, intent, route, List.of(), changes);
    }

    // ============ DTOs ============

    public record AssistantRequest(String message, Double latitude, Double longitude, Long routeId) {
    }

    public record AssistantReply(
            String reply,
            AssistantIntent intent,
            RouteResponse route,
            List<Recommendation> recommendations,
            List<String> changes
    ) {
    }

    public record MessageResponse(Long id, MessageRole role, String content, Long routeId, java.time.Instant createdAt) {
    }
}

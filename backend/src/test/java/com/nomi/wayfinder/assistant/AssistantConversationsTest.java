package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.area.AreaResolver;
import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.assistant.AssistantIntent.RouteEdit;
import com.nomi.wayfinder.assistant.AssistantService.AssistantReply;
import com.nomi.wayfinder.assistant.AssistantService.AssistantRequest;
import com.nomi.wayfinder.dto.RouteDtos.ReplanRequest;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.planning.PlanResult;
import com.nomi.wayfinder.planning.ReplanType;
import com.nomi.wayfinder.planning.RoutePlanner;
import com.nomi.wayfinder.repository.AssistantConversationRepository;
import com.nomi.wayfinder.repository.AssistantMessageRepository;
import com.nomi.wayfinder.repository.RouteRepository;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RouteMapper;
import com.nomi.wayfinder.service.RouteService;
import com.nomi.wayfinder.service.UserService;
import com.nomi.wayfinder.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Several chats per user: a message without a chat id starts a new chat, and route changes ("çok yorulduk") apply
 * only to the route of the chat they were written in. Real AssistantService and RouteService; the database,
 * planner and parser are faked.
 */
class AssistantConversationsTest {

    // Monday 28 September 2026, 12:00 in Istanbul
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T09:00:00Z"), ZoneId.of("Europe/Istanbul"));
    private static final long USER = 1L;
    private static final String PLAN = "2 kişiyiz rota planla";
    private static final String TIRED = "çok yorulduk";

    private final Map<Long, Route> routes = new HashMap<>();
    private final Map<Long, AssistantConversation> conversations = new HashMap<>();
    private final List<AssistantMessage> messages = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private RouteRepository routeRepository;
    private RouteService routeService;
    private AssistantService service;

    @BeforeEach
    void setUp() {
        routeRepository = mock(RouteRepository.class);
        when(routeRepository.save(any(Route.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(routeRepository.saveAndFlush(any(Route.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(routeRepository.findByIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> Optional.ofNullable(
                routes.get(inv.<Long>getArgument(0))).filter(r -> r.getUserId().equals(inv.getArgument(1))));

        RoutePlanner planner = mock(RoutePlanner.class);
        when(planner.plan(any())).thenReturn(new PlanResult(List.of(), List.of(), Optional.empty(), null));
        UserService userService = mock(UserService.class);
        when(userService.getPreferences(anyLong())).thenReturn(new UserPreferences(USER));
        routeService = spy(new RouteService(routeRepository, planner, userService, new RouteMapper(), CLOCK));

        AssistantConversationRepository conversationRepository = mock(AssistantConversationRepository.class);
        when(conversationRepository.save(any(AssistantConversation.class))).thenAnswer(inv -> {
            AssistantConversation c = inv.getArgument(0);
            if (c.getId() == null) {
                ReflectionTestUtils.setField(c, "id", ids.incrementAndGet());
            }
            conversations.put(c.getId(), c);
            return c;
        });
        when(conversationRepository.findByIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> Optional.ofNullable(
                conversations.get(inv.<Long>getArgument(0))).filter(c -> c.getUserId().equals(inv.getArgument(1))));

        AssistantMessageRepository messageRepository = mock(AssistantMessageRepository.class);
        when(messageRepository.save(any(AssistantMessage.class))).thenAnswer(inv -> {
            messages.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        IntentParser parser = mock(IntentParser.class);
        when(parser.parse(anyString(), any())).thenAnswer(inv -> switch (inv.<String>getArgument(0)) {
            case PLAN -> new AssistantIntent(IntentType.PLAN_ROUTE, null, List.of(), null, "RULES");
            case TIRED -> new AssistantIntent(IntentType.REPLAN, null, List.of(RouteEdit.of(ReplanType.TIRED)), null, "RULES");
            default -> new AssistantIntent(IntentType.UNKNOWN, null, List.of(), null, "RULES");
        });
        AreaResolver areaResolver = mock(AreaResolver.class);
        when(areaResolver.resolve(any(), anyString(), any(), any())).thenReturn(Optional.empty());

        service = new AssistantService(parser, routeService, mock(RecommendationService.class),
                mock(WeatherService.class), new ResponseComposer(), messageRepository, conversationRepository,
                areaResolver, CLOCK);
    }

    @Test
    void aMessageWithoutAConversationIdAlwaysStartsANewConversation() {
        AssistantReply first = send(PLAN, null);
        AssistantReply second = send("merhaba", null);

        assertThat(first.conversationId()).isNotNull();
        assertThat(second.conversationId()).isNotNull().isNotEqualTo(first.conversationId());
        // The new chat does not inherit the first chat's route
        assertThat(conversations.get(second.conversationId()).getRouteId()).isNull();
        assertThat(messages).filteredOn(m -> m.getConversationId().equals(second.conversationId()))
                .extracting(AssistantMessage::getRouteId).containsOnlyNulls();
    }

    @Test
    void theFirstMessageBecomesTheTitleAndTheRouteIsLinkedToItsChat() {
        AssistantReply reply = send(PLAN, null);

        AssistantConversation chat = conversations.get(reply.conversationId());
        assertThat(chat.getTitle()).isEqualTo(PLAN);
        assertThat(chat.getRouteId()).isEqualTo(reply.route().id());
        assertThat(messages).extracting(AssistantMessage::getConversationId).containsOnly(chat.getId());
    }

    @Test
    void aReplanInConversationBNeverChangesConversationAsRoute() {
        AssistantReply a = send(PLAN, null);
        AssistantReply b = send(PLAN, null);
        long routeA = a.route().id();
        long routeB = b.route().id();
        assertThat(routeA).isNotEqualTo(routeB);

        AssistantReply reply = send(TIRED, b.conversationId());

        assertThat(reply.conversationId()).isEqualTo(b.conversationId());
        assertThat(reply.route().id()).isEqualTo(routeB);
        verify(routeService).replanRoute(argThat(r -> r.getId() == routeB), any(ReplanRequest.class));
        verify(routeService, never()).replanRoute(argThat(r -> r.getId() == routeA), any(ReplanRequest.class));
        assertThat(routes.get(routeA).getWalkingTolerance()).isNotEqualTo(WalkingTolerance.LOW);
        assertThat(routes.get(routeB).getWalkingTolerance()).isEqualTo(WalkingTolerance.LOW);
    }

    @Test
    void aReplanInAChatWithoutARouteSaysSoAndTouchesNoRoute() {
        send(PLAN, null);
        AssistantReply empty = send("merhaba", null);

        AssistantReply reply = send(TIRED, empty.conversationId());

        assertThat(reply.route()).isNull();
        assertThat(reply.reply()).startsWith("Bu sohbette henüz bir rota yok.");
        verify(routeService, never()).replanRoute(any(), any());
    }

    @Test
    void aReplanOfAPastDaysRouteIsRefusedWithAHint() {
        AssistantReply chat = send(PLAN, null);
        routes.get(chat.route().id()).setDate(LocalDate.of(2026, 9, 27));

        AssistantReply reply = send(TIRED, chat.conversationId());

        assertThat(reply.reply()).isEqualTo("Bu rota geçmiş bir güne ait; yeni bir rota oluşturabilirsin.");
        verify(routeService, never()).replanRoute(any(), any());
    }

    @Test
    void anotherUsersConversationLooksMissing() {
        AssistantReply chat = send(PLAN, null);

        assertThatThrownBy(() -> service.handle(2L, new AssistantRequest(TIRED, 41.0, 29.0, null, chat.conversationId())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ============ route lifecycle ============

    @Test
    void replanningOrChangingStopsOfAPastRouteIs409() {
        Route past = pastRoute();

        assertThatThrownBy(() -> routeService.replan(USER, past.getId(),
                new ReplanRequest(ReplanType.TIRED, 41.0, 29.0, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Bu rota geçmiş bir güne ait; yeni bir rota oluşturabilirsin.");
                });
        assertThatThrownBy(() -> routeService.updateStopStatus(USER, past.getId(), 1L, StopStatus.VISITED))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void aPastDraftRouteIsExpiredWhenRead() {
        Route past = pastRoute();

        assertThat(routeService.getRoute(USER, past.getId()).status()).isEqualTo(RouteStatus.EXPIRED);
    }

    @Test
    void onlyTodaysRouteIsCurrent() {
        when(routeRepository.findFirstByUserIdAndDateAndStatusInOrderByUpdatedAtDesc(anyLong(), any(), anyCollection()))
                .thenReturn(Optional.empty());

        assertThat(routeService.findCurrentRoute(USER)).isEmpty();

        // Past routes are expired first, then only today's date is asked for
        verify(routeRepository).expireBefore(USER, LocalDate.of(2026, 9, 28));
        verify(routeRepository).findFirstByUserIdAndDateAndStatusInOrderByUpdatedAtDesc(
                eq(USER), eq(LocalDate.of(2026, 9, 28)), argThat(s -> s.containsAll(
                        List.of(RouteStatus.DRAFT, RouteStatus.ACTIVE)) && s.size() == 2));
    }

    // ============ titles ============

    @Test
    void longFirstMessagesAreShortenedAtAWordBoundary() {
        String title = AssistantService.titleFrom("2 kişiyiz, 700 TL bütçemiz var, kahvaltı ve kahve istiyoruz");

        assertThat(title).hasSizeLessThanOrEqualTo(AssistantService.TITLE_LENGTH).endsWith("…");
        assertThat(title).isEqualTo("2 kişiyiz, 700 TL bütçemiz var…");
        assertThat(AssistantService.titleFrom("  Yakında   kahve öner ")).isEqualTo("Yakında kahve öner");
    }

    // ============ helpers ============

    // Shared by the other assistant tests: saving a chat returns it
    static AssistantConversationRepository conversationRepository() {
        AssistantConversationRepository repository = mock(AssistantConversationRepository.class);
        when(repository.save(any(AssistantConversation.class))).thenAnswer(inv -> inv.getArgument(0));
        return repository;
    }

    private AssistantReply send(String message, Long conversationId) {
        return service.handle(USER, new AssistantRequest(message, 41.0, 29.0, null, conversationId));
    }

    private Route pastRoute() {
        Route route = new Route();
        route.setUserId(USER);
        route.setTitle("27 Eylül Pazar Rotası");
        route.setDate(LocalDate.of(2026, 9, 27));
        route.setStartLocation(41.0, 29.0);
        route.setStartTime(LocalTime.of(9, 0));
        route.setEndTime(LocalTime.of(22, 0));
        route.setWalkingTolerance(WalkingTolerance.MEDIUM);
        return store(route);
    }

    private Route store(Route route) {
        if (route.getId() == null) {
            ReflectionTestUtils.setField(route, "id", ids.incrementAndGet());
        }
        routes.put(route.getId(), route);
        return route;
    }
}

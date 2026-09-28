package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.RouteDtos.*;
import com.nomi.wayfinder.entity.*;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.planning.*;
import com.nomi.wayfinder.repository.RouteRepository;
import com.nomi.wayfinder.weather.WeatherForecast;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class RouteService {

    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_END = LocalTime.of(22, 0);
    private static final DateTimeFormatter TITLE_DATE =
            DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.forLanguageTag("tr-TR"));
    private static final DateTimeFormatter TITLE_DATE_EN =
            DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH);

    private final RouteRepository routeRepository;
    private final RoutePlanner planner;
    private final UserService userService;
    private final RouteMapper routeMapper;
    private final Clock clock;

    public RouteService(
            RouteRepository routeRepository,
            RoutePlanner planner,
            UserService userService,
            RouteMapper routeMapper,
            Clock clock
    ) {
        this.routeRepository = routeRepository;
        this.planner = planner;
        this.userService = userService;
        this.routeMapper = routeMapper;
        this.clock = clock;
    }

    // ================= PLAN =================

    @Transactional
    public RouteResponse planRoute(Long userId, RoutePlanRequest request) {
        return routeMapper.toResponse(createRoute(userId, request));
    }

    @Transactional
    public Route createRoute(Long userId, RoutePlanRequest request) {
        UserPreferences preferences = userService.getPreferences(userId);

        LocalDate today = LocalDate.now(clock);
        LocalDate date = request.date() != null ? request.date() : today;
        if (date.isBefore(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Route date cannot be in the past");
        }

        LocalTime start = request.startTime() != null ? request.startTime() : defaultStart(date);
        LocalTime end = request.endTime() != null ? request.endTime() : defaultEnd(start);
        if (start.equals(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "startTime and endTime cannot be the same");
        }

        int partySize = request.partySize() != null ? request.partySize() : preferences.getDefaultPartySize();
        Integer budget = request.budget() != null ? request.budget() : preferences.getDefaultBudget();
        WalkingTolerance tolerance = request.walkingTolerance() != null
                ? request.walkingTolerance() : preferences.getWalkingTolerance();
        List<String> interests = request.interests() != null && !request.interests().isEmpty()
                ? Interests.normalize(request.interests()) : preferences.getInterests();

        List<PlanningSlot> slots = DayTemplate.slotsFor(request.stops(), start, end);
        if (slots.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "No stops fit into the requested time window");
        }

        PlanResult result = planner.plan(new PlanningRequest(
                request.latitude(), request.longitude(), date, start, end,
                partySize, budget, tolerance, interests, slots, Set.of(), false));

        Route route = new Route();
        route.setUserId(userId);
        route.setTitle(request.title() != null && !request.title().isBlank()
                ? request.title().trim() : defaultTitle(date));
        route.setDate(date);
        route.setStartLocation(request.latitude(), request.longitude());
        route.setStartTime(start);
        route.setEndTime(end);
        route.setPartySize(partySize);
        route.setBudget(budget);
        route.setWalkingTolerance(tolerance);
        route.setInterests(interests);
        applyResult(route, result, List.of());

        return routeRepository.save(route);
    }

    /**
     * Saves a plan made from a ready planner request (a popular route the user starts): the same request as the
     * preview, so the route has the same stops as long as places, opening hours and the forecast did not change.
     */
    @Transactional
    public RouteResponse createPlannedRoute(Long userId, String title, PlanningRequest request) {
        PlanResult result = planner.plan(request);

        Route route = new Route();
        route.setUserId(userId);
        route.setTitle(title);
        route.setDate(request.date());
        route.setStartLocation(request.startLatitude(), request.startLongitude());
        route.setStartTime(request.startTime());
        route.setEndTime(request.endTime());
        route.setPartySize(request.partySize());
        route.setBudget(request.budget());
        route.setWalkingTolerance(request.walkingTolerance());
        route.setInterests(new ArrayList<>(request.interests()));
        applyResult(route, result, List.of());

        return routeMapper.toResponse(routeRepository.save(route));
    }

    // ================= READ / UPDATE =================

    @Transactional(readOnly = true)
    public List<RouteSummary> listRoutes(Long userId, boolean savedOnly) {
        List<Route> routes = savedOnly
                ? routeRepository.findByUserIdAndSavedTrueOrderByCreatedAtDesc(userId)
                : routeRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return routes.stream().map(routeMapper::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public RouteResponse getRoute(Long userId, Long routeId) {
        return routeMapper.toResponse(findRoute(userId, routeId));
    }

    @Transactional
    public RouteResponse updateRoute(Long userId, Long routeId, RouteUpdateRequest request) {
        Route route = findRoute(userId, routeId);

        if (request.status() != null) {
            route.setStatus(request.status());
        }
        if (request.saved() != null) {
            route.setSaved(request.saved());
        }
        if (request.title() != null) {
            route.setTitle(request.title().trim());
        }

        return routeMapper.toResponse(route);
    }

    @Transactional
    public void deleteRoute(Long userId, Long routeId) {
        routeRepository.delete(findRoute(userId, routeId));
    }

    @Transactional
    public RouteResponse updateStopStatus(Long userId, Long routeId, Long stopId, StopStatus status) {
        Route route = findRoute(userId, routeId);
        findStop(route, stopId).setStatus(status);

        // First visited stop means the trip has started
        if (status == StopStatus.VISITED && route.getStatus() == RouteStatus.DRAFT) {
            route.setStatus(RouteStatus.ACTIVE);
        }
        // Nothing left to do -> completed
        if (route.getStops().stream().noneMatch(s -> s.getStatus() == StopStatus.PLANNED)) {
            route.setStatus(RouteStatus.COMPLETED);
        }

        return routeMapper.toResponse(route);
    }

    // The route the user is most likely talking about (assistant, home screen)
    @Transactional(readOnly = true)
    public Optional<Route> findCurrentRoute(Long userId) {
        return routeRepository.findFirstByUserIdAndStatusInOrderByUpdatedAtDesc(
                userId, List.of(RouteStatus.ACTIVE, RouteStatus.DRAFT));
    }

    @Transactional(readOnly = true)
    public Optional<RouteSummary> findCurrentRouteSummary(Long userId) {
        return findCurrentRoute(userId).map(routeMapper::toSummary);
    }

    // ================= REPLAN =================

    @Transactional
    public ReplanResponse replan(Long userId, Long routeId, ReplanRequest request) {
        Route route = findRoute(userId, routeId);
        List<String> changes = replanRoute(route, request);
        return new ReplanResponse(routeMapper.toResponse(route), changes);
    }

    /**
     * Keeps visited/skipped stops, then re-plans the remaining ones from the user's current
     * location and time. Stops that are still fine stay "pinned" so the route does not change more than needed.
     */
    @Transactional
    public List<String> replanRoute(Route route, ReplanRequest request) {
        if (route.getStatus() == RouteStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Route is already completed");
        }

        List<RouteStop> done = route.getStops().stream()
                .filter(s -> s.getStatus() != StopStatus.PLANNED).toList();
        List<RouteStop> remaining = route.getStops().stream()
                .filter(s -> s.getStatus() == StopStatus.PLANNED).toList();

        List<PlanningSlot> slots = new ArrayList<>(remaining.stream()
                .map(s -> PlanningSlot.existing(s.getStopType(), s.getPlannedStart(), s.getPlace().getId()))
                .toList());

        Set<Long> excluded = new HashSet<>();
        done.forEach(s -> excluded.add(s.getPlace().getId()));

        WalkingTolerance tolerance = route.getWalkingTolerance();
        List<String> interests = new ArrayList<>(route.getInterests());
        boolean assumeWet = route.isAssumeWet();
        List<String> changes = new ArrayList<>();

        switch (request.type()) {
            case TIRED -> {
                tolerance = WalkingTolerance.LOW;
                // Drop outdoor sightseeing, keep meals/coffee/dessert
                slots.removeIf(slot -> slot.type() == StopType.SIGHTSEEING && isOutdoor(remaining, slot));
                // Remaining stops stay pinned; the planner swaps pinned places that are now too far (LOW tolerance)
                boolean restNext = !slots.isEmpty()
                        && (slots.getFirst().type() == StopType.COFFEE || slots.getFirst().type() == StopType.DESSERT);
                if (!restNext) {
                    // Pull a planned coffee forward as the rest stop instead of adding a second one
                    slots.removeIf(slot -> slot.type() == StopType.COFFEE);
                    slots.addFirst(PlanningSlot.next(StopType.COFFEE, 30));
                    changes.add(Texts.t("Yakında kısa bir dinlenme molası ekledim.", "I added a short rest stop nearby."));
                }
                changes.add(Texts.t("Yürüme mesafelerini kısalttım ve açık alan gezilerini çıkardım.",
                        "I shortened the walks and removed outdoor sightseeing."));
            }
            case WEATHER_CHANGED -> {
                // Pinned outdoor places are replaced by the planner when it is wet
                assumeWet = true;
                changes.add(Texts.t("Yağış nedeniyle açık alanları kapalı mekanlarla değiştirdim.",
                        "Because of the rain I replaced outdoor places with indoor ones."));
            }
            case LESS_WALKING -> {
                tolerance = WalkingTolerance.LOW;
                slots.replaceAll(PlanningSlot::unpinned);
                changes.add(Texts.t("Rotayı birbirine daha yakın mekanlarla yeniden düzenledim.",
                        "I rearranged the route with places closer to each other."));
            }
            case REMOVE_STOP -> {
                RouteStop target = findRemainingStop(remaining, request.stopId());
                slots.removeIf(slot -> Objects.equals(slot.pinnedPlaceId(), target.getPlace().getId()));
                excluded.add(target.getPlace().getId());
            }
            case REPLACE_STOP -> {
                RouteStop target = findRemainingStop(remaining, request.stopId());
                slots.replaceAll(slot -> Objects.equals(slot.pinnedPlaceId(), target.getPlace().getId())
                        ? slot.unpinned() : slot);
                excluded.add(target.getPlace().getId());
            }
            case ADD_STOP -> {
                if (request.stopType() == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "stopType is required for ADD_STOP");
                }
                slots.add(PlanningSlot.of(request.stopType()));
                slots.sort(Comparator.comparing(PlanningSlot::targetTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
            }
            case ADD_INTEREST -> {
                if (request.interest() == null || request.interest().isBlank()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "interest is required for ADD_INTEREST");
                }
                String interest = Interests.normalize(List.of(request.interest())).getFirst();
                if (!interests.contains(interest)) {
                    interests.add(interest);
                }
                slots.replaceAll(slot -> slot.type() == StopType.SIGHTSEEING ? slot.unpinned() : slot);
                long sightseeing = slots.stream().filter(s -> s.type() == StopType.SIGHTSEEING).count();
                if (sightseeing < 2) {
                    slots.addFirst(PlanningSlot.next(StopType.SIGHTSEEING, null));
                }
            }
        }

        if (slots.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "No remaining stops to plan");
        }

        LocalTime now = LocalTime.now(clock);
        boolean today = route.getDate().equals(LocalDate.now(clock));
        // Re-plan from "now" during the trip; before the trip starts keep the original start
        LocalTime start = today && now.isAfter(route.getStartTime()) ? roundUp(now) : route.getStartTime();
        // Never before the end of the last visited stop
        Optional<LocalTime> lastDoneEnd = done.stream()
                .filter(s -> s.getStatus() == StopStatus.VISITED)
                .map(RouteStop::getPlannedEnd)
                .max(Comparator.naturalOrder());
        if (!today && lastDoneEnd.isPresent() && lastDoneEnd.get().isAfter(start)) {
            start = lastDoneEnd.get();
        }
        LocalTime end = route.getEndTime();
        // Plans that already ran past their end time get a couple more hours
        if (today && !end.isAfter(start) && !route.getEndTime().isBefore(route.getStartTime())) {
            end = start.plusHours(3);
        }

        Integer remainingBudget = route.getBudget() == null ? null
                : route.getBudget() - done.stream()
                .filter(s -> s.getStatus() == StopStatus.VISITED)
                .mapToInt(s -> s.getPlace().getEstimatedCost() == null ? 0
                        : s.getPlace().getEstimatedCost() * route.getPartySize())
                .sum();

        PlanResult result = planner.plan(new PlanningRequest(
                request.latitude(), request.longitude(), route.getDate(), start, end,
                route.getPartySize(), remainingBudget, tolerance, interests, slots, excluded, assumeWet));

        changes.addAll(describeChanges(remaining, result.stops()));

        route.setWalkingTolerance(tolerance);
        route.setAssumeWet(assumeWet);
        route.setInterests(interests);
        applyResult(route, result, done);
        // Flush so new stops get ids (a second edit in the same request may refer to them)
        routeRepository.saveAndFlush(route);

        return changes;
    }

    // ================= HELPERS =================

    private void applyResult(Route route, PlanResult result, List<RouteStop> keptStops) {
        List<RouteStop> stops = new ArrayList<>(keptStops);

        for (PlannedStop planned : result.stops()) {
            RouteStop stop = new RouteStop();
            stop.setPlace(planned.place());
            stop.setStopType(planned.type());
            stop.setPlannedStart(planned.start());
            stop.setPlannedEnd(planned.end());
            stop.setDistanceFromPreviousMeters(planned.distanceFromPreviousMeters());
            stop.setWalkingMinutes(planned.walkingMinutes());
            stop.setReasons(planned.reasons());
            stops.add(stop);
        }

        route.replaceStops(stops);
        route.setNotes(result.notes());
        route.setWeatherAdvice(result.weatherAdvice());

        Optional<WeatherForecast.DaySummary> summary = result.forecast()
                .map(f -> f.summarize(route.getStartTime(), route.getEndTime()));
        route.setWeatherCondition(summary.map(s -> s.condition().name()).orElse(null));
        route.setWeatherTemperature(summary.map(WeatherForecast.DaySummary::maxTemperature).orElse(null));
    }

    private static List<String> describeChanges(List<RouteStop> before, List<PlannedStop> after) {
        Set<Long> beforeIds = new LinkedHashSet<>();
        before.forEach(s -> beforeIds.add(s.getPlace().getId()));
        Set<Long> afterIds = new LinkedHashSet<>();
        after.forEach(s -> afterIds.add(s.place().getId()));

        List<String> changes = new ArrayList<>();
        before.stream()
                .filter(s -> !afterIds.contains(s.getPlace().getId()))
                .forEach(s -> changes.add(Texts.t("Çıkarıldı: ", "Removed: ") + s.getPlace().getName()));
        after.stream()
                .filter(s -> !beforeIds.contains(s.place().getId()))
                .forEach(s -> changes.add(Texts.t("Eklendi: ", "Added: ") + s.place().getName()
                        + " (" + s.type().getLabel() + ", " + s.start() + ")"));
        if (changes.isEmpty()) {
            changes.add(Texts.t("Mekanlar aynı kaldı, saatleri güncelledim.",
                    "The places stayed the same; I updated the times."));
        }
        return changes;
    }

    private static boolean isOutdoor(List<RouteStop> remaining, PlanningSlot slot) {
        return remaining.stream()
                .filter(s -> Objects.equals(s.getPlace().getId(), slot.pinnedPlaceId()))
                .anyMatch(s -> !s.getPlace().isIndoor());
    }

    private static RouteStop findRemainingStop(List<RouteStop> remaining, Long stopId) {
        if (stopId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "stopId is required");
        }
        return remaining.stream()
                .filter(s -> Objects.equals(s.getId(), stopId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "Stop " + stopId + " is not a remaining stop of this route"));
    }

    @Transactional(readOnly = true)
    public Route getRouteEntity(Long userId, Long routeId) {
        return findRoute(userId, routeId);
    }

    public RouteResponse toResponse(Route route) {
        return routeMapper.toResponse(route);
    }

    private Route findRoute(Long userId, Long routeId) {
        // Filtering by user id means other users' routes look like they do not exist
        return routeRepository.findByIdAndUserId(routeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Route not found with id: " + routeId));
    }

    private static RouteStop findStop(Route route, Long stopId) {
        return route.getStops().stream()
                .filter(s -> Objects.equals(s.getId(), stopId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Stop not found with id: " + stopId));
    }

    // "27 Eylül Pazar Rotası" / "Sunday, 27 September route"
    public static String defaultTitle(LocalDate date) {
        return Texts.english()
                ? dayName(date) + " route"
                : dayName(date) + " Rotası";
    }

    // "27 Eylül Pazar" / "Sunday, 27 September"
    public static String dayName(LocalDate date) {
        return Texts.english() ? date.format(TITLE_DATE_EN) : date.format(TITLE_DATE);
    }

    private LocalTime defaultStart(LocalDate date) {
        if (!date.equals(LocalDate.now(clock))) {
            return DEFAULT_START;
        }
        LocalTime now = roundUp(LocalTime.now(clock));
        return now.isBefore(LocalTime.of(8, 0)) ? DEFAULT_START : now;
    }

    private static LocalTime defaultEnd(LocalTime start) {
        return start.isBefore(DEFAULT_END.minusHours(3)) ? DEFAULT_END : start.plusHours(3);
    }

    // Next quarter hour
    private static LocalTime roundUp(LocalTime time) {
        int minutes = time.getHour() * 60 + time.getMinute();
        int rounded = (int) Math.ceil(minutes / 15.0) * 15;
        return LocalTime.of((rounded / 60) % 24, rounded % 60);
    }
}

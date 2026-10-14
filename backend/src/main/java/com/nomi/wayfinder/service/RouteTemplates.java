package com.nomi.wayfinder.service;

import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.RouteStop;
import com.nomi.wayfinder.entity.StopStatus;
import com.nomi.wayfinder.planning.PlanResult;
import com.nomi.wayfinder.planning.PlanningRequest;
import com.nomi.wayfinder.planning.PlanningSlot;
import com.nomi.wayfinder.planning.PopularRouteBuilder;
import com.nomi.wayfinder.planning.PopularRouteBuilder.PlannedStopRef;
import com.nomi.wayfinder.planning.RouteSimilarity;
import com.nomi.wayfinder.planning.RouteSimilarity.Shape;
import com.nomi.wayfinder.repository.RouteRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Routes Nomi users liked, as templates for new routes. A route counts by its places in order: everyone who got the
 * same places adds their like (or dislike) to it, and only routes liked more than disliked are offered.
 * <p>
 * A template is never copied as it is: its places are pinned and the planner plans the day again for the new date,
 * party and budget (closed places and outdoor places in the rain are replaced). If too much of it had to change, or
 * it no longer fits the budget, the route is planned from scratch instead ({@link #acceptable}).
 */
@Service
public class RouteTemplates {

    /**
     * Every user's vote on their route: saving it ("Kaydet", the heart) or 👍 counts for it, 👎 against it (and wins
     * over a save). One row per route: route_id, user_id, liked, voted_at.
     */
    public static final String VOTES = """
            SELECT r.id AS route_id, r.user_id, coalesce(f.liked, TRUE) AS liked,
                   coalesce(f.updated_at, r.updated_at) AS voted_at
            FROM routes r LEFT JOIN route_feedback f ON f.route_id = r.id
            WHERE f.route_id IS NOT NULL OR r.saved""";

    static final int MAX_CANDIDATES = 60;
    // At least this share of the liked route's places must survive the re-planning
    static final double KEEP_SHARE = 0.6;

    /**
     * @param netLikes likes minus dislikes of every route with the same places
     */
    public record Template(Route route, double score, int netLikes) {
    }

    private record Feedback(long routeId, long userId, boolean liked) {
    }

    private final JdbcTemplate jdbc;
    private final RouteRepository routes;

    public RouteTemplates(JdbcTemplate jdbc, RouteRepository routes) {
        this.jdbc = jdbc;
        this.routes = routes;
    }

    /** The liked route nearby that best answers the request; empty = plan from scratch. */
    @Transactional(readOnly = true)
    public Optional<Template> find(long userId, Shape wanted) {
        List<Feedback> nearby = jdbc.query("SELECT v.route_id, v.user_id, v.liked FROM (" + VOTES + """
                ) v JOIN routes r ON r.id = v.route_id
                WHERE ST_DWithin(r.start_location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                ORDER BY v.voted_at DESC LIMIT ?""",
                (rs, i) -> new Feedback(rs.getLong("route_id"), rs.getLong("user_id"), rs.getBoolean("liked")),
                wanted.longitude(), wanted.latitude(), RouteSimilarity.MAX_START_METERS, MAX_CANDIDATES);
        if (nearby.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Route> byId = routes.findAllById(nearby.stream().map(Feedback::routeId).toList()).stream()
                .collect(Collectors.toMap(Route::getId, r -> r));

        // Net likes per place sequence; a sequence the asking user disliked is never offered to them
        Map<String, Integer> net = new HashMap<>();
        Set<String> dislikedByUser = new HashSet<>();
        for (Feedback f : nearby) {
            Route route = byId.get(f.routeId());
            if (route == null) {
                continue;
            }
            String key = sequenceKey(route);
            net.merge(key, f.liked() ? 1 : -1, Integer::sum);
            if (!f.liked() && f.userId() == userId) {
                dislikedByUser.add(key);
            }
        }

        return nearby.stream()
                .filter(Feedback::liked)
                .map(f -> byId.get(f.routeId()))
                .filter(Objects::nonNull)
                .filter(RouteTemplates::usable)
                .filter(r -> net.getOrDefault(sequenceKey(r), 0) > 0 && !dislikedByUser.contains(sequenceKey(r)))
                .map(r -> {
                    OptionalDouble score = RouteSimilarity.match(wanted, shape(r));
                    return score.isPresent() ? new Template(r, score.getAsDouble(), net.get(sequenceKey(r))) : null;
                })
                .filter(Objects::nonNull)
                .max(Comparator.comparingDouble(Template::score).thenComparingInt(Template::netLikes));
    }

    /** The planner input that plans the template again with the new day's own settings. */
    public static PlanningRequest replay(Template template, PlanningRequest base) {
        Route route = template.route();
        // Same rhythm, moved to the asked start time
        Duration shift = route.getStartTime() == null || base.startTime() == null ? Duration.ZERO
                : Duration.between(route.getStartTime(), base.startTime());
        List<PlannedStopRef> stops = kept(route).stream()
                .map(s -> new PlannedStopRef(s.getStopType(), s.getPlace().getId(), s.getPlannedStart().plus(shift),
                        minutes(s)))
                .toList();
        List<PlanningSlot> slots = PopularRouteBuilder.replay(stops, base.startLatitude(), base.startLongitude(),
                base.date()).slots();
        return new PlanningRequest(base.startLatitude(), base.startLongitude(), base.date(), base.startTime(),
                base.endTime(), base.partySize(), base.budget(), base.walkingTolerance(), base.interests(), slots,
                base.excludedPlaceIds(), base.assumeWet(), base.savedPlaceIds());
    }

    /** Enough of the liked places are still in it, and it fits the budget. */
    public static boolean acceptable(Template template, PlanResult result, Integer budget, int partySize) {
        Set<Long> liked = kept(template.route()).stream().map(s -> s.getPlace().getId()).collect(Collectors.toSet());
        long stillThere = result.stops().stream().filter(s -> liked.contains(s.place().getId())).count();
        if (liked.isEmpty() || stillThere < Math.ceil(liked.size() * KEEP_SHARE)) {
            return false;
        }
        int cost = result.stops().stream().mapToInt(s -> s.totalCost(partySize)).sum();
        return budget == null || cost <= budget;
    }

    /** A liked route in the terms RouteSimilarity compares. */
    static Shape shape(Route route) {
        List<RouteStop> stops = kept(route);
        List<Integer> prices = stops.stream().map(s -> s.getPlace().getEstimatedCost()).filter(Objects::nonNull).toList();
        Integer perPerson = prices.isEmpty() ? null : prices.stream().mapToInt(Integer::intValue).sum();
        return new Shape(route.getStartLocation().getY(), route.getStartLocation().getX(),
                stops.stream().map(RouteStop::getStopType).toList(), route.getInterests(), perPerson,
                route.getWalkingTolerance(), route.getStartTime());
    }

    /** Places in visiting order: the same key for everyone who got the same route. */
    public static String sequenceKey(Route route) {
        return kept(route).stream().map(s -> Long.toString(s.getPlace().getId(), 36)).collect(Collectors.joining("-"));
    }

    // The stops that make the route (skipped ones were not part of the day the user liked)
    static List<RouteStop> kept(Route route) {
        return route.getStops().stream().filter(s -> s.getStatus() != StopStatus.SKIPPED).toList();
    }

    // Every place still there for users (not hidden, not removed / suspect by the owner)
    static boolean usable(Route route) {
        List<RouteStop> stops = kept(route);
        return stops.size() >= 2 && stops.stream().map(RouteStop::getPlace)
                .allMatch(p -> !p.isHidden() && p.getReview() == null);
    }

    private static int minutes(RouteStop stop) {
        long minutes = Duration.between(stop.getPlannedStart(), stop.getPlannedEnd()).toMinutes();
        return (int) (minutes < 0 ? minutes + 1440 : minutes);
    }
}

package com.nomi.wayfinder.service;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.PlaceImage;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularPlace;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteResponse;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularStop;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.RouteStop;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.planning.PlanningRequest;
import com.nomi.wayfinder.planning.PopularRouteBuilder;
import com.nomi.wayfinder.planning.PopularRouteBuilder.PlannedStopRef;
import com.nomi.wayfinder.repository.RouteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "Bu rotayı beğendin mi?" and the area's community routes: routes Nomi users liked, most liked first, in the same
 * cards as the popular routes. Starting one plans it again for the chosen day (RouteTemplates.replay), so closed
 * places and outdoor places in the rain are replaced.
 */
@Service
public class CommunityRouteService {

    static final int MAX_ROUTES = 10;
    static final int MAX_LIKED_SCANNED = 500;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** The owner's verdict on their route; null = not given. */
    public record Feedback(Boolean liked) {
    }

    private record Liked(long routeId, boolean liked) {
    }

    private final JdbcTemplate jdbc;
    private final RouteRepository routes;
    private final RouteService routeService;
    private final CityService cityService;
    private final DistrictService districtService;
    private final Clock clock;

    public CommunityRouteService(JdbcTemplate jdbc, RouteRepository routes, RouteService routeService,
                                 CityService cityService, DistrictService districtService, Clock clock) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.routeService = routeService;
        this.cityService = cityService;
        this.districtService = districtService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Feedback feedback(long userId, long routeId) {
        ownRoute(userId, routeId);
        return new Feedback(jdbc.query("SELECT liked FROM route_feedback WHERE route_id = ?",
                rs -> rs.next() ? (Boolean) rs.getBoolean("liked") : null, routeId));
    }

    /** Only the route's owner, only a route with stops; liking again replaces a dislike. */
    @Transactional
    public Feedback setFeedback(long userId, long routeId, boolean liked) {
        Route route = ownRoute(userId, routeId);
        if (RouteTemplates.kept(route).isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "A route without stops cannot be rated");
        }
        jdbc.update("""
                INSERT INTO route_feedback (route_id, user_id, liked, created_at, updated_at) VALUES (?, ?, ?, now(), now())
                ON CONFLICT (route_id) DO UPDATE SET liked = EXCLUDED.liked, updated_at = now()""",
                routeId, userId, liked);
        return new Feedback(liked);
    }

    @Transactional
    public Feedback removeFeedback(long userId, long routeId) {
        ownRoute(userId, routeId);
        jdbc.update("DELETE FROM route_feedback WHERE route_id = ?", routeId);
        return new Feedback(null);
    }

    /**
     * The area's routes users liked, most net likes first (places in the same order count as one route).
     * Shown as they were liked; starting one checks them again for the day.
     */
    @Transactional(readOnly = true)
    public List<PopularRouteResponse> communityRoutes(String citySlug, String districtSlug) {
        List<Liked> feedback = feedbackIn(citySlug, districtSlug);
        Map<Long, Route> byId = new LinkedHashMap<>();
        routes.findAllById(feedback.stream().map(Liked::routeId).toList()).forEach(r -> byId.put(r.getId(), r));

        // Per place sequence: net likes and the latest liked route as its face
        Map<String, Integer> net = new LinkedHashMap<>();
        Map<String, Route> face = new LinkedHashMap<>();
        for (Liked f : feedback) {
            Route route = byId.get(f.routeId());
            if (route == null || !RouteTemplates.usable(route)) {
                continue;
            }
            String key = RouteTemplates.sequenceKey(route);
            net.merge(key, f.liked() ? 1 : -1, Integer::sum);
            if (f.liked()) {
                face.putIfAbsent(key, route);
            }
        }
        LocalDate today = LocalDate.now(clock);
        return face.entrySet().stream()
                .filter(e -> net.get(e.getKey()) > 0)
                .sorted(Comparator.comparingInt((Map.Entry<String, Route> e) -> net.get(e.getKey())).reversed())
                .limit(MAX_ROUTES)
                .map(e -> toResponse(e.getKey(), e.getValue(), net.get(e.getKey()), today))
                .toList();
    }

    /** "Bu rotayı başlat": the liked route planned again for the user's day, with its likes' places pinned. */
    @Transactional
    public RouteResponse start(long userId, String citySlug, String districtSlug, String key, LocalDate date) {
        PopularRouteResponse chosen = communityRoutes(citySlug, districtSlug).stream()
                .filter(r -> r.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Community route not found: " + key));
        LocalDate day = date != null ? date : LocalDate.now(clock);
        List<PlannedStopRef> stops = chosen.stops().stream()
                .map(s -> new PlannedStopRef(s.type(), s.place().id(), LocalTime.parse(s.time(), HH_MM),
                        s.durationMinutes()))
                .toList();
        PlanningRequest request = PopularRouteBuilder.replay(stops, chosen.startLatitude(), chosen.startLongitude(), day);
        return routeService.createPlannedRoute(userId, chosen.title(), request, chosen.startLabel());
    }

    // Votes (saves, 👍, 👎: RouteTemplates.VOTES) on routes starting in the district / city / anywhere in Türkiye
    // (no city), newest first
    private List<Liked> feedbackIn(String citySlug, String districtSlug) {
        String select = "SELECT v.route_id, v.liked FROM (" + RouteTemplates.VOTES + ") v "
                + "JOIN routes r ON r.id = v.route_id ";
        String order = " ORDER BY v.voted_at DESC LIMIT ?";
        RowMapper<Liked> row =
                (rs, i) -> new Liked(rs.getLong("route_id"), rs.getBoolean("liked"));
        if (citySlug == null || citySlug.isBlank()) {
            return jdbc.query(select + order, row, MAX_LIKED_SCANNED);
        }
        CityService.City city = cityService.findBySlug(citySlug)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + citySlug));
        if (districtSlug == null || districtSlug.isBlank()) {
            return jdbc.query(select + "JOIN cities c ON c.id = ? WHERE ST_Contains(c.geom, r.start_location::geometry)"
                    + order, row, city.id(), MAX_LIKED_SCANNED);
        }
        long districtId = districtService.findIdBySlug(city.id(), districtSlug)
                .orElseThrow(() -> new ResourceNotFoundException("District not found: " + districtSlug));
        return jdbc.query(select + "JOIN districts d ON d.id = ? WHERE ST_Contains(d.geom, r.start_location::geometry)"
                + order, row, districtId, MAX_LIKED_SCANNED);
    }

    private Route ownRoute(long userId, long routeId) {
        return routes.findByIdAndUserId(routeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Route not found: " + routeId));
    }

    static PopularRouteResponse toResponse(String key, Route route, int likes, LocalDate date) {
        List<RouteStop> stops = RouteTemplates.kept(route);
        List<PopularStop> shown = stops.stream().map(CommunityRouteService::toStop).toList();
        List<Integer> prices = stops.stream().map(s -> s.getPlace().getEstimatedCost()).filter(Objects::nonNull).toList();
        Place first = stops.getFirst().getPlace();
        String startLabel = route.getStartLabel() != null ? route.getStartLabel() : first.getDisplayName();
        return new PopularRouteResponse(
                "c-" + key,
                title(stops),
                Texts.t("Nomi kullanıcılarının bu bölgede beğendiği bir gün.",
                        "A day Nomi users liked in this area."),
                Texts.t(likes + " kişi favoriledi", likes == 1 ? "A favourite of 1 user" : "A favourite of " + likes + " users"),
                startLabel,
                route.getStartLocation().getY(),
                route.getStartLocation().getX(),
                date,
                shown,
                stops.stream().mapToInt(RouteStop::getWalkingMinutes).sum(),
                prices.isEmpty() ? null : prices.stream().mapToInt(Integer::intValue).sum(),
                stops.size() - prices.size());
    }

    // "Viktor Levi, Moda Sahili ve 2 durak daha"
    static String title(List<RouteStop> stops) {
        List<String> names = new ArrayList<>(stops.stream().map(s -> s.getPlace().getDisplayName()).toList());
        if (names.size() <= 2) {
            return String.join(Texts.t(" ve ", " and "), names);
        }
        int more = names.size() - 2;
        return names.get(0) + ", " + names.get(1) + Texts.t(" ve " + more + " durak daha", " and " + more + " more");
    }

    private static PopularStop toStop(RouteStop stop) {
        Place place = stop.getPlace();
        long minutes = Duration.between(stop.getPlannedStart(), stop.getPlannedEnd()).toMinutes();
        return new PopularStop(
                stop.getPlannedStart().format(HH_MM),
                (int) (minutes < 0 ? minutes + 1440 : minutes),
                stop.getStopType(),
                stop.getStopType().getLabel(),
                stop.getWalkingMinutes(),
                stop.getDistanceFromPreviousMeters(),
                new PopularPlace(place.getId(), place.getDisplayName(), place.getCategory(), place.getLatitude(),
                        place.getLongitude(), PlaceImage.of(place), place.getEstimatedCost(), place.isVerified(),
                        place.getDistrictName(), place.getPopularity()));
    }
}

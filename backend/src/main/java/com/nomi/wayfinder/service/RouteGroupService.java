package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.RouteDtos.GroupInfo;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.dto.RouteDtos.StopVotes;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.repository.RouteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Group plans: the owner shares a route with an invite code (the /join/{code} link), friends join it and vote on its
 * places. Members see the route and (with a pass) change it like the owner; only the owner shares, renames, saves or
 * deletes it. Joining and voting are free, so a friend without a pass still has a say.
 */
@Service
public class RouteGroupService {

    // Members besides the owner: a route is planned for at most 20 people (RoutePlanRequest.partySize)
    static final int MAX_MEMBERS = 19;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RouteRepository routeRepository;
    private final JdbcTemplate jdbc;
    private final RouteMapper routeMapper;

    public RouteGroupService(RouteRepository routeRepository, JdbcTemplate jdbc, RouteMapper routeMapper) {
        this.routeRepository = routeRepository;
        this.jdbc = jdbc;
        this.routeMapper = routeMapper;
    }

    // The invite code; the same one until the owner turns sharing off
    @Transactional
    public String share(Long userId, Long routeId) {
        Route route = owned(userId, routeId);
        if (route.getShareToken() == null) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            route.setShareToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }
        return route.getShareToken();
    }

    // Nobody new can join; the members stay
    @Transactional
    public void unshare(Long userId, Long routeId) {
        owned(userId, routeId).setShareToken(null);
    }

    @Transactional
    public Long join(Long userId, String token) {
        Route route = routeRepository.findByShareToken(token)
                .orElseThrow(() -> new ResourceNotFoundException(Texts.t("Davet kodu geçersiz ya da kapatılmış.",
                        "The invite code is wrong or no longer active.")));
        if (route.getUserId().equals(userId) || isMember(route.getId(), userId)) {
            return route.getId();
        }
        Integer members = jdbc.queryForObject("SELECT count(*) FROM route_members WHERE route_id = ?", Integer.class,
                route.getId());
        if (members != null && members >= MAX_MEMBERS) {
            throw new BusinessException(HttpStatus.CONFLICT, Texts.t("Bu grup dolu.", "This group is full."));
        }
        jdbc.update("INSERT INTO route_members (route_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                route.getId(), userId);
        return route.getId();
    }

    // A member leaves; their votes go with them
    @Transactional
    public void leave(Long userId, Long routeId) {
        jdbc.update("DELETE FROM route_votes WHERE route_id = ? AND user_id = ?", routeId, userId);
        jdbc.update("DELETE FROM route_members WHERE route_id = ? AND user_id = ?", routeId, userId);
    }

    @Transactional
    public RouteResponse vote(Long userId, Long routeId, Long placeId, int vote) {
        Route route = findAccessible(userId, routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route not found with id: " + routeId));
        if (route.getStops().stream().noneMatch(s -> s.getPlace().getId().equals(placeId))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Place " + placeId + " is not on this route");
        }
        if (vote == 0) {
            jdbc.update("DELETE FROM route_votes WHERE route_id = ? AND place_id = ? AND user_id = ?",
                    routeId, placeId, userId);
        } else {
            jdbc.update("""
                    INSERT INTO route_votes (route_id, place_id, user_id, vote) VALUES (?, ?, ?, ?)
                    ON CONFLICT (route_id, place_id, user_id) DO UPDATE SET vote = EXCLUDED.vote
                    """, routeId, placeId, userId, vote);
        }
        return toResponse(route, userId);
    }

    public boolean isMember(Long routeId, Long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM route_members WHERE route_id = ? AND user_id = ?)",
                Boolean.class, routeId, userId));
    }

    // Routes of others the user joined
    public List<Long> memberRouteIds(Long userId) {
        return jdbc.queryForList("SELECT route_id FROM route_members WHERE user_id = ?", Long.class, userId);
    }

    // The user's own route or one of a group they are in; anybody else's looks like it does not exist
    public Optional<Route> findAccessible(Long userId, Long routeId) {
        return routeRepository.findById(routeId)
                .filter(r -> r.getUserId().equals(userId) || isMember(routeId, userId));
    }

    public RouteResponse toResponse(Route route, Long userId) {
        boolean owner = route.getUserId().equals(userId);
        // Everyone in the group, the owner first, then in the order they joined
        List<String> members = jdbc.queryForList("""
                SELECT u.display_name FROM users u
                LEFT JOIN route_members m ON m.user_id = u.id AND m.route_id = ?
                WHERE u.id = ? OR m.route_id IS NOT NULL
                ORDER BY (u.id = ?) DESC, m.joined_at
                """, String.class, route.getId(), route.getUserId(), route.getUserId());
        GroupInfo group = new GroupInfo(owner, owner ? route.getShareToken() : null, members);

        Map<Long, StopVotes> votes = new HashMap<>();
        jdbc.query("""
                SELECT place_id, count(*) FILTER (WHERE vote = 1) AS likes, count(*) FILTER (WHERE vote = -1) AS dislikes,
                       coalesce(max(vote) FILTER (WHERE user_id = ?), 0) AS mine
                FROM route_votes WHERE route_id = ? GROUP BY place_id
                """, rs -> {
            votes.put(rs.getLong("place_id"),
                    new StopVotes(rs.getInt("likes"), rs.getInt("dislikes"), rs.getInt("mine")));
        }, userId, route.getId());
        return routeMapper.toResponse(route, group, votes);
    }

    private Route owned(Long userId, Long routeId) {
        return routeRepository.findByIdAndUserId(routeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Route not found with id: " + routeId));
    }
}

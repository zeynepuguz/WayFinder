package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.RouteDtos.*;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RouteResponse planRoute(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RoutePlanRequest request) {
        return routeService.planRoute(CurrentUser.id(jwt), request);
    }

    // "Gezi Rotalarım" (all) and "Kaydedilenler" (saved=true)
    @GetMapping
    public List<RouteSummary> listRoutes(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean saved
    ) {
        return routeService.listRoutes(CurrentUser.id(jwt), saved);
    }

    @GetMapping("/{id}")
    public RouteResponse getRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return routeService.getRoute(CurrentUser.id(jwt), id);
    }

    @PatchMapping("/{id}")
    public RouteResponse updateRoute(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody RouteUpdateRequest request
    ) {
        return routeService.updateRoute(CurrentUser.id(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        routeService.deleteRoute(CurrentUser.id(jwt), id);
    }

    @PatchMapping("/{id}/stops/{stopId}")
    public RouteResponse updateStopStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @PathVariable Long stopId,
            @Valid @RequestBody StopStatusRequest request
    ) {
        return routeService.updateStopStatus(CurrentUser.id(jwt), id, stopId, request.status());
    }

    @PostMapping("/{id}/replan")
    public ReplanResponse replan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody ReplanRequest request
    ) {
        return routeService.replan(CurrentUser.id(jwt), id, request);
    }
}

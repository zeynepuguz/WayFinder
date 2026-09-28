package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteResponse;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteStartRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.PopularRouteService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/routes/popular")
public class PopularRouteController {

    private final PopularRouteService popularRouteService;

    public PopularRouteController(PopularRouteService popularRouteService) {
        this.popularRouteService = popularRouteService;
    }

    /**
     * Public: themed days (history, food, coffee & dessert, parks & views) planned from real places, starting at the
     * district's (else the city's) centre at 10:00. Only themes with at least 3 real stops. Cached for a few hours.
     */
    @GetMapping
    public List<PopularRouteResponse> popularRoutes(
            @RequestParam String city,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return popularRouteService.popularRoutes(city, district, popularRouteService.dayOrToday(date));
    }

    // Paid like every route creation (AccessInterceptor: /api/v1/routes/**); returns the normal route
    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteResponse start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PopularRouteStartRequest request) {
        return popularRouteService.start(CurrentUser.id(jwt), request);
    }
}

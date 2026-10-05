package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteResponse;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteStartRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.PopularRouteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
     * Public: up to 5 routes of the area's most popular sights (Wikipedia popularity) that lie close together, in
     * walking order from 09:30, with lunch / coffee / dessert planned in between. Cached for a few hours.
     */
    @GetMapping
    public List<PopularRouteResponse> popularRoutes(
            @RequestParam @Size(max = 100) String city,
            @RequestParam(required = false) @Size(max = 100) String district,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return popularRouteService.popularRoutes(city, district, popularRouteService.dayOrToday(date));
    }

    // Saves the previewed route (by its key); paid like every route creation (AccessInterceptor: /api/v1/routes/**)
    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteResponse start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PopularRouteStartRequest request) {
        return popularRouteService.start(CurrentUser.id(jwt), request);
    }
}

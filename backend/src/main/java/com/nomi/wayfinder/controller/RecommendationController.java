package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.RecommendationService;
import com.nomi.wayfinder.service.RecommendationService.Recommendation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final Clock clock;

    public RecommendationController(RecommendationService recommendationService, Clock clock) {
        this.recommendationService = recommendationService;
        this.clock = clock;
    }

    // type is optional: without it we suggest what fits the current time of day
    @GetMapping
    public List<Recommendation> recommend(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam(required = false) StopType type,
            @RequestParam(defaultValue = "5") @Positive @Max(20) int limit
    ) {
        StopType stopType = type != null ? type : RecommendationService.suggestedTypeAt(LocalTime.now(clock));
        return recommendationService.recommend(lat, lon, stopType, CurrentUser.idOrNull(jwt), limit);
    }
}

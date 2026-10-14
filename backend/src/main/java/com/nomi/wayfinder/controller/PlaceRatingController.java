package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.planning.RatingReason;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.PlaceCommunityService;
import com.nomi.wayfinder.service.PlaceCommunityService.RatingView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Star ratings of food and drink places by Nomi's users. Reading is open to everyone (with the asker's own stars
 * when signed in); rating needs an account but no pass: the more people rate, the better everyone's routes get.
 */
@RestController
@RequestMapping("/api/v1/places/{id}/rating")
public class PlaceRatingController {

    public record RateRequest(@Min(1) @Max(5) int stars) {
    }

    // "Neden bu puanı verdin?": empty clears the answers
    public record ReasonsRequest(@NotNull @Size(max = RatingReason.MAX_PER_RATING) List<@NotNull RatingReason> reasons) {
    }

    private final PlaceCommunityService service;

    public PlaceRatingController(PlaceCommunityService service) {
        this.service = service;
    }

    @GetMapping
    public RatingView rating(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.view(id, CurrentUser.idOrNull(jwt));
    }

    // Rating again replaces the earlier stars
    @PutMapping
    public RatingView rate(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                           @Valid @RequestBody RateRequest request) {
        return service.rate(CurrentUser.id(jwt), id, request.stars());
    }

    // Only after rating; the answers must fit the stars (liked at 4-5, disliked at 1-2, either at 3)
    @PutMapping("/reasons")
    public RatingView reasons(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                              @Valid @RequestBody ReasonsRequest request) {
        return service.setReasons(CurrentUser.id(jwt), id, request.reasons());
    }

    @DeleteMapping
    public RatingView remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.removeRating(CurrentUser.id(jwt), id);
    }
}

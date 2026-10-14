package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteResponse;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.CommunityRouteService;
import com.nomi.wayfinder.service.CommunityRouteService.Feedback;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * "Bu rotayı beğendin mi?" on the user's own routes, and the area's routes Nomi users liked (same cards as the popular
 * routes; listing is open, starting one needs a pass like every new route).
 */
@RestController
@RequestMapping("/api/v1/routes")
public class CommunityRouteController {

    public record FeedbackRequest(@NotNull Boolean liked) {
    }

    /**
     * @param city     the list's city slug; empty = the Türkiye-wide list
     * @param district the list's district slug (with a city)
     * @param key      a key of that list ("c-...")
     */
    public record StartRequest(@Size(max = 100) String city, @Size(max = 100) String district,
                               @NotBlank @Size(max = 200) String key, LocalDate date) {
    }

    private final CommunityRouteService service;

    public CommunityRouteController(CommunityRouteService service) {
        this.service = service;
    }

    @GetMapping("/{id}/feedback")
    public Feedback feedback(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.feedback(CurrentUser.id(jwt), id);
    }

    @PutMapping("/{id}/feedback")
    public Feedback setFeedback(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                @Valid @RequestBody FeedbackRequest request) {
        return service.setFeedback(CurrentUser.id(jwt), id, request.liked());
    }

    @DeleteMapping("/{id}/feedback")
    public Feedback removeFeedback(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.removeFeedback(CurrentUser.id(jwt), id);
    }

    // No city: the most favourite routes of all Türkiye; a city: its own; a district too: only the district's
    @GetMapping("/community")
    public List<PopularRouteResponse> community(@RequestParam(required = false) @Size(max = 100) String city,
                                                @RequestParam(required = false) @Size(max = 100) String district) {
        return service.communityRoutes(city, district);
    }

    @PostMapping("/community/start")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteResponse start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartRequest request) {
        return service.start(CurrentUser.id(jwt), request.city(), request.district(), request.key(), request.date());
    }
}

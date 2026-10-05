package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.RouteDtos.*;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.RouteGroupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Group plans. Sharing is under /routes (the owner's paid route); joining, voting and leaving are under
 * /shared-routes, which needs no pass (config/WebConfig).
 */
@RestController
@RequestMapping("/api/v1")
public class RouteGroupController {

    private final RouteGroupService groups;

    public RouteGroupController(RouteGroupService groups) {
        this.groups = groups;
    }

    @PostMapping("/routes/{id}/share")
    public ShareResponse share(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return new ShareResponse(groups.share(CurrentUser.id(jwt), id));
    }

    @DeleteMapping("/routes/{id}/share")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unshare(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        groups.unshare(CurrentUser.id(jwt), id);
    }

    @PostMapping("/shared-routes/{token:[A-Za-z0-9_-]{1,32}}/join")
    public JoinResponse join(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return new JoinResponse(groups.join(CurrentUser.id(jwt), token));
    }

    @PostMapping("/shared-routes/{id:\\d+}/votes")
    public RouteResponse vote(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                              @Valid @RequestBody VoteRequest request) {
        return groups.vote(CurrentUser.id(jwt), id, request.placeId(), request.vote());
    }

    @DeleteMapping("/shared-routes/{id:\\d+}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        groups.leave(CurrentUser.id(jwt), id);
    }
}

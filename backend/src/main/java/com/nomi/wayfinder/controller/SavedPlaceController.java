package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.SavedPlaceService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// "Kaydedilenler" - saved places. Saved routes are /api/v1/routes?saved=true
@RestController
@RequestMapping("/api/v1/saved/places")
public class SavedPlaceController {

    private final SavedPlaceService savedPlaceService;

    public SavedPlaceController(SavedPlaceService savedPlaceService) {
        this.savedPlaceService = savedPlaceService;
    }

    @GetMapping
    public List<PlaceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return savedPlaceService.list(CurrentUser.id(jwt));
    }

    // PUT: saving twice is not an error (idempotent)
    @PutMapping("/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@AuthenticationPrincipal Jwt jwt, @PathVariable Long placeId) {
        savedPlaceService.save(CurrentUser.id(jwt), placeId);
    }

    @DeleteMapping("/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable Long placeId) {
        savedPlaceService.remove(CurrentUser.id(jwt), placeId);
    }
}

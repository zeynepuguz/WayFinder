package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.AuthDtos.PreferencesRequest;
import com.nomi.wayfinder.dto.AuthDtos.PreferencesResponse;
import com.nomi.wayfinder.dto.AuthDtos.UserResponse;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getProfile(CurrentUser.id(jwt));
    }

    @PutMapping("/preferences")
    public PreferencesResponse updatePreferences(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PreferencesRequest request
    ) {
        return userService.updatePreferences(CurrentUser.id(jwt), request);
    }
}

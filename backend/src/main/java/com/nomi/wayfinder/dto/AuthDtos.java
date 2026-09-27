package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.entity.UserRole;
import com.nomi.wayfinder.entity.WalkingTolerance;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

// Request/response bodies for auth and user profile endpoints
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 100) String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {
    }

    public record UserResponse(
            Long id,
            String email,
            String displayName,
            UserRole role,
            PreferencesResponse preferences
    ) {
    }

    public record PreferencesResponse(
            WalkingTolerance walkingTolerance,
            int defaultPartySize,
            Integer defaultBudget,
            List<String> interests
    ) {
    }

    public record PreferencesRequest(
            @NotNull WalkingTolerance walkingTolerance,
            @Min(1) @Max(20) int defaultPartySize,
            @PositiveOrZero Integer defaultBudget,
            @Size(max = 20) List<@NotBlank String> interests
    ) {
    }
}

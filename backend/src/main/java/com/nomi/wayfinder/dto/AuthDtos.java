package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.billing.BillingService;
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
            @NotBlank @Size(max = 100) String password
    ) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\s*\\d{6}\\s*", message = "must be the 6-digit code") String code,
            @NotBlank @Size(min = 8, max = 100) String newPassword
    ) {
    }

    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {
    }

    public record UserResponse(
            Long id,
            String email,
            String displayName,
            UserRole role,
            PreferencesResponse preferences,
            BillingService.AccessStatus access
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
            @Size(max = 20) List<@NotBlank @Size(max = 40) String> interests
    ) {
    }
}

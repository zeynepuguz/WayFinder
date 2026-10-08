package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.billing.BillingService;
import com.nomi.wayfinder.entity.UserRole;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.security.EmailCodeService;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

// Request/response bodies for auth and user profile endpoints
public final class AuthDtos {

    private AuthDtos() {
    }

    // Letters of any language, with spaces, apostrophes and hyphens inside ("Ayşe Nur", "O'Brien", "Kaya-Demir")
    static final String NAME_PATTERN = "\\s*\\p{L}+(?:[ '’-]\\p{L}+)*\\s*";
    static final String CODE_PATTERN = "\\s*\\d{6}\\s*";

    public record RegisterRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = NAME_PATTERN, message = "must be a name") String firstName,
            @NotBlank @Size(max = 50) @Pattern(regexp = NAME_PATTERN, message = "must be a name") String lastName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 100) String password
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(max = 100) String password
    ) {
    }

    // The e-mailed code that finishes sign-up / sign-in
    public record VerifyCodeRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = CODE_PATTERN, message = "must be the 6-digit code") String code
    ) {
    }

    public record ResendCodeRequest(@NotBlank @Email String email, @NotNull EmailCodeService.Purpose purpose) {
    }

    /**
     * Sign-up / sign-in step one passed: a code went to the e-mail.
     *
     * @param email the address it went to, partly hidden ("ze****@gmail.com")
     */
    public record CodeSentResponse(String email, long validMinutes, long resendAfterSeconds) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = CODE_PATTERN, message = "must be the 6-digit code") String code,
            @NotBlank @Size(min = 8, max = 100) String newPassword
    ) {
    }

    /**
     * Signed in: a short access token for the API, and a refresh token for a new pair (POST /auth/refresh).
     *
     * @param refreshExpiresAt the session ends then unless it is refreshed before (7 days after the last use)
     */
    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, String refreshToken,
                               Instant refreshExpiresAt, UserResponse user) {
    }

    public record UserResponse(
            Long id,
            String email,
            String displayName,
            String firstName,
            String lastName,
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

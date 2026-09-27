package com.nomi.wayfinder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

// Typed view of the "nomi.*" settings in application.yml (values come from .env)
@ConfigurationProperties(prefix = "nomi")
public record NomiProperties(
        String timezone,
        Security security,
        Weather weather,
        Ai ai,
        RateLimit rateLimit,
        Billing billing,
        Mail mail
) {

    /**
     * @param from        sender address of outgoing e-mails
     * @param devLogCodes write password reset codes to the log instead of failing silently when
     *                    no SMTP server is configured; local development only
     */
    public record Mail(String from, boolean devLogCodes) {
    }

    /**
     * @param devMode allows free "purchases" through /billing/dev/purchase; must stay false in production
     * @param prices  TL price per plan; must match the prices set in Google Play Console
     * @param freeAccessEmails accounts (e.g. the owner's) that get unlimited access without paying
     */
    public record Billing(
            boolean devMode,
            String googlePlayPackageName,
            String googlePlayServiceAccountFile,
            java.util.Map<com.nomi.wayfinder.entity.AccessPlan, Integer> prices,
            List<String> freeAccessEmails
    ) {

        public boolean isFreeAccess(String email) {
            return email != null && freeAccessEmails != null
                    && freeAccessEmails.stream().anyMatch(free -> free.trim().equalsIgnoreCase(email.trim()));
        }
    }

    public record Security(
            String jwtSecret,
            long jwtExpirationMinutes,
            String adminEmail,
            String adminPassword,
            List<String> corsAllowedOrigins
    ) {
    }

    public record Weather(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }

    /**
     * @param dailyLimitPerUser messages per user per day sent to the AI service (bot/abuse protection);
     *                          beyond it the rule-based parser answers
     */
    public record Ai(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout,
                     int dailyLimitPerUser) {

        public boolean enabled() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record RateLimit(int requestsPerMinute) {
    }
}

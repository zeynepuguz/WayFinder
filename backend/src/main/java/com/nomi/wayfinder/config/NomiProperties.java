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
        RateLimit rateLimit
) {

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

    public record Ai(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {

        public boolean enabled() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record RateLimit(int requestsPerMinute) {
    }
}

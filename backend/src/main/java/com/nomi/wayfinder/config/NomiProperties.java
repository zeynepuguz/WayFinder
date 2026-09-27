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
        Mail mail,
        Osm osm,
        Images images
) {

    /**
     * Place photos from Wikimedia Commons (free licenses only), found through the wikidata / Commons
     * references the OSM import stores. See media/WikimediaImageResolver.
     *
     * @param resolveAfterImport look up photos right after every OSM import (in the background)
     * @param resolveCron        Spring cron for the periodic pass ("-" disables it)
     * @param recheckAfter       places checked longer ago than this are looked up again
     * @param maxPlacesPerRun    upper bound per pass, so one pass never runs for hours
     * @param thumbWidth         width of the thumbnail used as image_url
     * @param batchDelay         pause between API requests (be gentle with Wikimedia)
     * @param maxAttempts        tries per API request (maxlag / network errors)
     * @param retryDelay         wait before the next try
     * @param wikidataMaxlag     maxlag sent to Wikidata (0 = not sent). Wikidata also reports its query
     *                           service lag here, often above 5 s for hours, which would block plain reads
     * @param commonsMaxlag      maxlag sent to Commons (0 = not sent)
     */
    public record Images(
            boolean resolveAfterImport,
            String resolveCron,
            Duration recheckAfter,
            int maxPlacesPerRun,
            String wikidataApiUrl,
            String commonsApiUrl,
            int thumbWidth,
            Duration connectTimeout,
            Duration readTimeout,
            Duration batchDelay,
            int maxAttempts,
            Duration retryDelay,
            int wikidataMaxlag,
            int commonsMaxlag
    ) {
    }

    /**
     * OpenStreetMap place import (all of Istanbul) through the Overpass API.
     *
     * @param importOnStartup    import once in the background when the database has no OSM places yet
     * @param refreshCron        Spring cron for the periodic refresh ("-" disables it)
     * @param overpassEndpoints  tried in order; the main server is often busy
     * @param maxAttempts        rounds over all endpoints before giving up
     * @param retryDelay         wait between rounds (grows with each round)
     */
    public record Osm(
            boolean importOnStartup,
            String refreshCron,
            List<String> overpassEndpoints,
            Duration connectTimeout,
            Duration readTimeout,
            int maxAttempts,
            Duration retryDelay
    ) {
    }

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

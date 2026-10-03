package com.nomi.wayfinder.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Google Places API (New) for "is this place still there?" ("nomi.google-places.*" in application.yml).
 *
 * @param apiKey       Places API key (GOOGLE_PLACES_API_KEY in .env); blank = no check, the place page falls back
 *                     to a Google Maps search around our point
 * @param radiusMeters a Google place counts as ours within this distance of our pin
 * @param timeout      connect / read timeout; a slow answer must not hold the place page
 */
@ConfigurationProperties(prefix = "nomi.google-places")
public record GooglePlacesProperties(String apiKey, int radiusMeters, Duration timeout) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}

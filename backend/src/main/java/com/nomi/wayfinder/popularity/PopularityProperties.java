package com.nomi.wayfinder.popularity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Place popularity from open Wikimedia signals ("nomi.popularity.*"), see PlacePopularityService.
 *
 * @param updateAfterImport look the imported city's places up right after every OSM import (in the background)
 * @param cron              Spring cron for the periodic pass over every city ("-" disables it); monthly by default
 * @param recheckAfter      places checked longer ago than this are looked up again
 * @param maxPlacesPerRun   upper bound per pass
 * @param pageviewDays      days of pageviews that are summed (the API allows at most 60)
 * @param wikidataApiUrl    wbgetentities (sitelinks)
 * @param wikipediaApiUrl   a Wikipedia's action API, "{lang}" = tr / en (query&prop=pageviews)
 * @param batchDelay        pause after every request
 * @param maxAttempts       tries per request (network errors, maxlag, 429)
 * @param retryDelay        wait before the next try when the server gives no Retry-After
 */
@ConfigurationProperties(prefix = "nomi.popularity")
public record PopularityProperties(
        boolean updateAfterImport,
        String cron,
        Duration recheckAfter,
        int maxPlacesPerRun,
        int pageviewDays,
        String wikidataApiUrl,
        String wikipediaApiUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration batchDelay,
        int maxAttempts,
        Duration retryDelay
) {
}

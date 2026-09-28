package com.nomi.wayfinder.popularity;

import com.nomi.wayfinder.popularity.WikiPopularityParser.ApiErrorException;
import com.nomi.wayfinder.popularity.WikiPopularityParser.PageviewPage;
import com.nomi.wayfinder.popularity.WikiPopularityParser.Sitelinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Wikidata sitelinks and Wikipedia pageviews, BATCH_SIZE items / articles per request.
 * Pageviews come from the Wikipedia action API (query&prop=pageviews: the daily "user" views of the Wikimedia
 * pageviews service for the last pageviewDays days), 50 articles per request. The REST per-article endpoint
 * (/metrics/pageviews/per-article) needs one request per article and soon answers 429: Wikimedia rate-limits
 * clients per IP, which made a pass over İstanbul take hours.
 * Wikimedia asks API clients for a descriptive User-Agent, a gentle pace and to back off when busy: every request
 * is followed by batchDelay; 429 waits for Retry-After (else retryDelay), maxlag / network errors retryDelay.
 */
@Component
public class WikiPopularityClient {

    private static final Logger log = LoggerFactory.getLogger(WikiPopularityClient.class);

    static final String USER_AGENT = "Nomi/1.0 (city guide app)";
    static final int BATCH_SIZE = 50;
    // Longest Retry-After we wait for; a longer one fails the request (the places are retried on the next pass)
    static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(2);

    private final PopularityProperties properties;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;

    public WikiPopularityClient(PopularityProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    /**
     * Wikidata ids (max BATCH_SIZE) -> their Wikipedia articles; unknown / deleted ids are absent (Wikidata fails
     * the whole request for one unknown id and names it: that id is dropped and the rest asked again).
     */
    public Map<String, Sitelinks> fetchSitelinks(List<String> wikidataIds) {
        List<String> ids = new ArrayList<>(wikidataIds);
        while (!ids.isEmpty()) {
            MultiValueMap<String, String> form = form();
            form.add("action", "wbgetentities");
            form.add("ids", String.join("|", ids));
            form.add("props", "sitelinks");
            try {
                return post(properties.wikidataApiUrl(), form, body -> WikiPopularityParser.sitelinks(body, jsonMapper));
            } catch (ApiErrorException e) {
                if (!"no-such-entity".equals(e.code()) || e.id() == null || !ids.remove(e.id())) {
                    throw e;
                }
                log.info("Popularity: Wikidata item {} does not exist, skipped", e.id());
            }
        }
        return Map.of();
    }

    /**
     * Views of up to BATCH_SIZE articles of one Wikipedia in the last pageviewDays days, per requested title
     * (articles the API reports nothing for are absent).
     *
     * @param language "tr" / "en"
     */
    public Map<String, Long> fetchViews(String language, List<String> titles) {
        Map<String, Long> views = new HashMap<>();
        String continuation = null;
        // The API reports pageviews for a limited number of pages per answer and continues with pvipcontinue
        for (int page = 0; page < 10; page++) {
            MultiValueMap<String, String> form = form();
            form.add("action", "query");
            form.add("prop", "pageviews");
            form.add("titles", String.join("|", titles));
            form.add("pvipdays", String.valueOf(properties.pageviewDays()));
            form.add("redirects", "1");
            form.add("formatversion", "2");
            if (continuation != null) {
                form.add("pvipcontinue", continuation);
            }
            PageviewPage answer = post(properties.wikipediaApiUrl().replace("{lang}", language), form,
                    body -> WikiPopularityParser.pageviews(body, titles, jsonMapper));
            answer.views().forEach((title, count) -> views.merge(title, count, Long::sum));
            continuation = answer.continuation();
            if (continuation == null) {
                break;
            }
        }
        return views;
    }

    private static MultiValueMap<String, String> form() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("format", "json");
        return form;
    }

    private <T> T post(String url, MultiValueMap<String, String> form, Function<String, T> parser) {
        int attempts = Math.max(1, properties.maxAttempts());
        RuntimeException last = null;
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                Duration wait = properties.retryDelay();
                try {
                    String body = restClient.post()
                            .uri(url)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(form)
                            .retrieve()
                            .body(String.class);
                    return parser.apply(body == null ? "{}" : body);
                } catch (ApiErrorException e) {
                    // Only a busy server is worth waiting for
                    if (!"maxlag".equals(e.code())) {
                        throw e;
                    }
                    last = e;
                } catch (HttpClientErrorException e) {
                    // 4xx other than 429 will not fix itself
                    if (e.getStatusCode().value() != 429) {
                        throw e;
                    }
                    last = e;
                    Duration retryAfter = retryAfter(e.getResponseHeaders());
                    if (retryAfter != null && retryAfter.compareTo(MAX_RETRY_AFTER) > 0) {
                        throw e;
                    }
                    if (retryAfter != null) {
                        wait = retryAfter;
                    }
                } catch (RuntimeException e) {
                    last = e;
                }
                log.warn("Popularity: Wikimedia request to {} failed (attempt {}/{}): {}", url, attempt, attempts,
                        shorten(last.getMessage()));
                if (attempt < attempts) {
                    sleep(wait);
                }
            }
            throw last;
        } finally {
            sleep(properties.batchDelay());
        }
    }

    // Retry-After in seconds; the HTTP-date form is not used by Wikimedia (null = not given / not understood)
    static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String shorten(String message) {
        if (message == null) {
            return null;
        }
        String flat = message.replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    private static void sleep(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Wikimedia", e);
        }
    }
}

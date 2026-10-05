package com.nomi.wayfinder.media;

import com.nomi.wayfinder.config.HttpClients;
import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.media.WikimediaParser.ApiErrorException;
import com.nomi.wayfinder.media.WikimediaParser.CommonsImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Wikidata (item -> P18 image) and Commons (file -> thumbnail, author, license) API calls, up to
 * BATCH_SIZE ids / titles per request. Wikimedia asks API clients for a descriptive User-Agent, a gentle
 * pace and to back off when the servers are busy: maxlag / network errors are retried after retryDelay,
 * and every request is followed by batchDelay.
 * Requests are POSTed as forms so titles with "&", "+" or non-ASCII letters are always encoded correctly.
 */
@Component
public class WikimediaClient {

    private static final Logger log = LoggerFactory.getLogger(WikimediaClient.class);

    static final String USER_AGENT = "Nomi/1.0 (city guide app; contact via site)";
    static final int BATCH_SIZE = 50;

    private final NomiProperties.Images properties;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;

    public WikimediaClient(NomiProperties nomiProperties, JsonMapper jsonMapper) {
        this.properties = nomiProperties.images();
        this.jsonMapper = jsonMapper;

        this.restClient = HttpClients.restClient(properties.connectTimeout(), properties.readTimeout())
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    /**
     * Wikidata ids (max BATCH_SIZE) -> P18 file name; ids without an image are absent.
     * Wikidata fails the whole request for one unknown (e.g. deleted) id and names it: that id is dropped
     * (it has no image) and the rest are asked again.
     */
    public Map<String, String> fetchImageFiles(List<String> wikidataIds) {
        List<String> ids = new ArrayList<>(wikidataIds);
        while (!ids.isEmpty()) {
            MultiValueMap<String, String> form = form(properties.wikidataMaxlag());
            form.add("action", "wbgetentities");
            form.add("ids", String.join("|", ids));
            form.add("props", "claims");
            try {
                return post(properties.wikidataApiUrl(), form, body -> WikimediaParser.imageFiles(body, jsonMapper));
            } catch (ApiErrorException e) {
                if (!"no-such-entity".equals(e.code()) || e.id() == null || !ids.remove(e.id())) {
                    throw e;
                }
                log.info("Place images: Wikidata item {} does not exist, skipped", e.id());
            }
        }
        return Map.of();
    }

    // Commons file names without "File:" (max BATCH_SIZE) -> usable image; unusable / missing files are absent
    public Map<String, CommonsImage> fetchImages(List<String> files) {
        MultiValueMap<String, String> form = form(properties.commonsMaxlag());
        form.add("action", "query");
        form.add("titles", String.join("|", files.stream().map(f -> "File:" + f).toList()));
        form.add("prop", "imageinfo");
        form.add("iiprop", "url|extmetadata");
        form.add("iiurlwidth", String.valueOf(properties.thumbWidth()));
        form.add("iiextmetadatafilter", "Artist|LicenseShortName|NonFree");
        form.add("iiextmetadatalanguage", "en");
        form.add("redirects", "1");
        form.add("formatversion", "2");
        return post(properties.commonsApiUrl(), form, body -> WikimediaParser.images(body, files, jsonMapper));
    }

    private static MultiValueMap<String, String> form(int maxlag) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("format", "json");
        if (maxlag > 0) {
            form.add("maxlag", String.valueOf(maxlag));
        }
        return form;
    }

    private <T> T post(String url, MultiValueMap<String, String> form, Function<String, T> parser) {
        int attempts = Math.max(1, properties.maxAttempts());
        RuntimeException last = null;
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
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
                    // Only a busy server is worth waiting for; other API errors will not fix themselves
                    if (!"maxlag".equals(e.code())) {
                        throw e;
                    }
                    last = e;
                } catch (RuntimeException e) {
                    last = e;
                }
                log.warn("Wikimedia request to {} failed (attempt {}/{}): {}", url, attempt, attempts, last.getMessage());
                if (attempt < attempts) {
                    sleep(properties.retryDelay());
                }
            }
            throw last;
        } finally {
            // Gentle pace between requests, successful or not
            sleep(properties.batchDelay());
        }
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

package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.config.NomiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Downloads Istanbul's cafes, restaurants, dessert shops, museums, sights, parks and culture venues
 * from the Overpass API (OpenStreetMap). ~12k elements, ~3.3 MB.
 * Overpass servers are often busy and then answer 200 with an HTML/XML error page, or JSON with a
 * runtime error in "remark": such answers are treated as failures and the next endpoint is tried.
 */
@Component
public class OverpassClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassClient.class);

    // Overpass answers 406 without a User-Agent
    static final String USER_AGENT = "Nomi/1.0 (city guide app)";

    // 3600223474 = the Istanbul province relation (223474) as an Overpass area
    static final String ISTANBUL_QUERY = """
            [out:json][timeout:180];
            area(id:3600223474)->.ist;
            (
              nwr["amenity"~"^(cafe|restaurant|ice_cream|theatre|arts_centre)$"]["name"](area.ist);
              nwr["shop"~"^(pastry|confectionery)$"]["name"](area.ist);
              nwr["tourism"~"^(museum|attraction|viewpoint)$"]["name"](area.ist);
              nwr["leisure"="park"]["name"](area.ist);
            );
            out center tags;
            """;

    private final NomiProperties.Osm properties;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;

    public OverpassClient(NomiProperties nomiProperties, JsonMapper jsonMapper) {
        this.properties = nomiProperties.osm();
        this.jsonMapper = jsonMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    public List<OverpassResponse.Element> fetchIstanbulPlaces() {
        String form = "data=" + URLEncoder.encode(ISTANBUL_QUERY, StandardCharsets.UTF_8);
        List<String> endpoints = properties.overpassEndpoints();
        int attempts = Math.max(1, properties.maxAttempts());
        RuntimeException last = null;

        for (int round = 1; round <= attempts; round++) {
            for (String endpoint : endpoints) {
                try {
                    log.info("OSM import: querying {} (round {}/{})", endpoint, round, attempts);
                    String body = restClient.post()
                            .uri(endpoint.trim())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(form)
                            .retrieve()
                            .body(String.class);
                    return parse(body, jsonMapper);
                } catch (RuntimeException e) {
                    last = e;
                    log.warn("OSM import: {} failed: {}", endpoint, e.getMessage());
                }
            }
            if (round < attempts) {
                sleep(properties.retryDelay().multipliedBy(round));
            }
        }
        throw new IllegalStateException("All Overpass endpoints failed", last);
    }

    // Rejects busy-server pages (HTML/XML with status 200) and JSON answers that report a runtime error
    static List<OverpassResponse.Element> parse(String body, JsonMapper jsonMapper) {
        String trimmed = body == null ? "" : body.stripLeading();
        if (!trimmed.startsWith("{")) {
            throw new IllegalStateException("Overpass did not answer with JSON: "
                    + trimmed.substring(0, Math.min(120, trimmed.length())).replaceAll("\\s+", " "));
        }
        OverpassResponse response = jsonMapper.readValue(trimmed, OverpassResponse.class);
        if (response.remark() != null && response.remark().toLowerCase(java.util.Locale.ROOT).contains("error")) {
            throw new IllegalStateException("Overpass reported an error: " + response.remark());
        }
        if (response.elements() == null) {
            throw new IllegalStateException("Overpass answer has no elements");
        }
        return response.elements();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Overpass", e);
        }
    }
}

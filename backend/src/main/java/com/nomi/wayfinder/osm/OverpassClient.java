package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.config.NomiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Downloads OpenStreetMap data from the Overpass API: Turkey's provinces (cities), and per city its districts,
 * named neighbourhoods and the cafes, restaurants, dessert shops, museums, sights, parks and culture venues
 * (Istanbul: ~12k elements, ~3.3 MB).
 * Overpass servers are often busy and then answer 200 with an HTML/XML error page, or JSON with a
 * runtime error in "remark": such answers are treated as failures and the next endpoint is tried.
 */
@Component
public class OverpassClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassClient.class);

    // Overpass answers 406 without a User-Agent
    static final String USER_AGENT = "Nomi/1.0 (city guide app)";

    // An OSM relation as an Overpass area: 3600000000 + relation id
    static final long AREA_ID_OFFSET = 3_600_000_000L;
    // Turkey (relation 174737)
    static final long TURKEY_RELATION_ID = 174737L;
    // İstanbul province (relation 223474)
    static final long ISTANBUL_RELATION_ID = 223474L;

    // Places of one city; %d = the city's Overpass area id
    static final String PLACES_QUERY = """
            [out:json][timeout:180];
            area(id:%d)->.city;
            (
              nwr["amenity"~"^(cafe|restaurant|ice_cream|theatre|arts_centre)$"]["name"](area.city);
              nwr["shop"~"^(pastry|confectionery)$"]["name"](area.city);
              nwr["tourism"~"^(museum|attraction|viewpoint)$"]["name"](area.city);
              nwr["leisure"="park"]["name"](area.city);
            );
            out center tags;
            """;

    // A city's districts (ilçe boundaries) with their member ways' geometry, to build polygons from
    static final String DISTRICTS_QUERY = """
            [out:json][timeout:180];
            area(id:%d)->.city;
            relation["boundary"="administrative"]["admin_level"="6"](area.city);
            out geom;
            """;

    // A city's named neighbourhoods ("Moda", "Kuzguncuk", "Kızılay")
    static final String AREAS_QUERY = """
            [out:json][timeout:180];
            area(id:%d)->.city;
            node["place"~"^(suburb|quarter|neighbourhood)$"]["name"](area.city);
            out;
            """;

    // Turkey's provinces (il boundaries, admin_level=4) with geometry. Relations of neighbouring countries that
    // share a border way come back too; the importer keeps only ISO3166-2 "TR-.." ones
    static final String PROVINCES_QUERY = """
            [out:json][timeout:600];
            area(id:%d)->.tr;
            relation["boundary"="administrative"]["admin_level"="4"](area.tr);
            out geom;
            """.formatted(AREA_ID_OFFSET + TURKEY_RELATION_ID);

    // The Istanbul place query as it always was (same filters, same area)
    static final String ISTANBUL_QUERY = placesQuery(ISTANBUL_RELATION_ID);

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

    public List<OverpassResponse.Element> fetchProvinces() {
        return fetch(PROVINCES_QUERY, true);
    }

    /**
     * @param relationId the city's (province's) OSM relation id, e.g. 223474 for İstanbul
     */
    public List<OverpassResponse.Element> fetchPlaces(long relationId) {
        return fetch(placesQuery(relationId), true);
    }

    public List<OverpassResponse.Element> fetchDistricts(long relationId) {
        return fetch(DISTRICTS_QUERY.formatted(areaId(relationId)), true);
    }

    public List<OverpassResponse.Element> fetchAreas(long relationId) {
        // Every province has named neighbourhoods; "none" is a mirror with a missing / stale area index
        return fetch(AREAS_QUERY.formatted(areaId(relationId)), true);
    }

    static String placesQuery(long relationId) {
        return PLACES_QUERY.formatted(areaId(relationId));
    }

    static long areaId(long relationId) {
        return AREA_ID_OFFSET + relationId;
    }

    List<OverpassResponse.Element> fetch(String query) {
        return fetch(query, false);
    }

    /**
     * Tries every endpoint, max-attempts rounds with a growing pause in between.
     *
     * @param requireElements an empty answer counts as a failure: a mirror whose area index is missing / stale
     *                        answers "no elements" for area queries instead of an error
     */
    List<OverpassResponse.Element> fetch(String query, boolean requireElements) {
        String form = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
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
                    List<OverpassResponse.Element> elements = parse(body, jsonMapper);
                    if (requireElements && elements.isEmpty()) {
                        throw new IllegalStateException("Overpass answered without elements");
                    }
                    return elements;
                } catch (HttpClientErrorException.TooManyRequests e) {
                    // This IP's quota on that server is used up for now: give it time before the next request
                    last = e;
                    log.warn("OSM import: {} is rate limiting us (429), waiting {} s", endpoint,
                            properties.retryDelay().toSeconds());
                    sleep(properties.retryDelay());
                } catch (RuntimeException e) {
                    last = e;
                    log.warn("OSM import: {} failed: {}", endpoint, shorten(e.getMessage()));
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

    // Busy-server answers are whole HTML pages
    private static String shorten(String message) {
        if (message == null) {
            return null;
        }
        String flat = message.replaceAll("\\s+", " ");
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
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

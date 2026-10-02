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
 * (Istanbul: ~26k elements in two queries).
 * Overpass servers are often busy and then answer 200 with an HTML/XML error page, or JSON with a
 * runtime error in "remark": such answers are treated as failures and the next endpoint is tried.
 */
@Component
public class OverpassClient implements OsmSource {

    private static final Logger log = LoggerFactory.getLogger(OverpassClient.class);

    // Overpass answers 406 without a User-Agent
    static final String USER_AGENT = "Nomi/1.0 (city guide app)";

    // An OSM relation as an Overpass area: 3600000000 + relation id
    static final long AREA_ID_OFFSET = 3_600_000_000L;
    // Turkey (relation 174737)
    static final long TURKEY_RELATION_ID = 174737L;
    // İstanbul province (relation 223474)
    static final long ISTANBUL_RELATION_ID = 223474L;

    /*
     * Places of one city, in three smaller queries (one query with everything runs into Overpass' time limit on busy
     * servers); %d = the city's Overpass area id. Few statements with broad filters are much faster than many narrow
     * ones: OsmPlaceMapper picks what to keep (e.g. only notable or historic places of worship as sights, bakeries
     * that are pastry / börek shops). "out center meta": tags plus when the element was last edited (old elements
     * without contact / hours data that no other source knows are likely closed, see overture/OverturePlaceImporter).
     */
    // Food and drink: cafes, restaurants, fast food (döner, köfte, pide), dessert shops, bakeries, coffee shops
    static final String FOOD_QUERY = """
            [out:json][timeout:300];
            area(id:%d)->.city;
            (
              nwr["amenity"~"^(cafe|restaurant|fast_food|food_court|ice_cream)$"]["name"](area.city);
              nwr["shop"~"^(pastry|confectionery|bakery|coffee)$"]["name"](area.city);
            );
            out center meta;
            """;

    // Markets and places of worship: supermarkets, bakkal; mosques, churches, synagogues, cemevleri (also those
    // mapped only by religion / denomination or by name: "... Cemevi" as a plain building)
    static final String MARKETS_WORSHIP_QUERY = """
            [out:json][timeout:300];
            area(id:%d)->.city;
            (
              nwr["shop"~"^(supermarket|convenience|grocery)$"]["name"](area.city);
              nwr["amenity"="place_of_worship"]["name"](area.city);
              nwr["religion"="jewish"]["name"](area.city);
              nwr["denomination"~"^(alevi|bektashi)"]["name"](area.city);
              nwr["name"~"[Cc]em ?[Ee]v|CEM ?EV|[Ss]inagog|SİNAGOG|[Hh]avra|HAVRA"](area.city);
            );
            out center meta;
            """;

    // Sights, culture and nature: museums, galleries, attractions, parks, gardens, historic sites, bazaars, beaches,
    // lighthouses, theatres (famous places of worship come with the worship query)
    static final String SIGHTS_QUERY = """
            [out:json][timeout:300];
            area(id:%d)->.city;
            (
              nwr["tourism"~"^(museum|gallery|attraction|viewpoint|zoo|aquarium|theme_park)$"]["name"](area.city);
              nwr["leisure"~"^(park|garden|nature_reserve)$"]["name"](area.city);
              nwr["historic"]["name"](area.city);
              nwr["amenity"~"^(theatre|arts_centre|marketplace)$"]["name"](area.city);
              nwr["natural"="beach"]["name"](area.city);
              nwr["man_made"="lighthouse"]["name"](area.city);
              nwr["tourism"="artwork"]["artwork_type"~"mural|graffiti"]["name"](area.city);
            );
            out center meta;
            """;
    // A city's districts (ilçe boundaries) with their member ways' geometry, to build polygons from
    static final String DISTRICTS_QUERY = """
            [out:json][timeout:180];
            area(id:%d)->.city;
            relation["boundary"="administrative"]["admin_level"="6"](area.city);
            out geom;
            """;

    // Areas of institutions a visitor does not walk into (campuses, schools, hospitals, prisons, military and industrial
    // zones), as polygons: closed ways and multipolygon relations with their geometry
    static final String INSTITUTIONS_QUERY = """
            [out:json][timeout:300];
            area(id:%d)->.city;
            (
              way["amenity"~"^(university|college|school|hospital|prison)$"](area.city);
              relation["amenity"~"^(university|college|school|hospital|prison)$"](area.city);
              way["landuse"~"^(military|industrial)$"](area.city);
              relation["landuse"~"^(military|industrial)$"](area.city);
              way["military"](area.city);
              relation["military"](area.city);
            );
            out geom;
            """;

    // The sea coastline in a box around the city (south, west, north, east). A bounding box instead of the city area:
    // provinces' boundaries often run along the coast, and inland cities simply get no ways
    static final String COASTLINE_QUERY = """
            [out:json][timeout:180];
            way["natural"="coastline"](%s);
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
     * Food / drink, sights, markets and places of worship of a city (three Overpass queries with properties' callDelay
     * in between), merged: an element several queries return (a historic cafe, a famous mosque) is kept once.
     *
     * @param relationId the city's (province's) OSM relation id, e.g. 223474 for İstanbul
     */
    public List<OverpassResponse.Element> fetchPlaces(long relationId) {
        List<String> queries = placesQueries(relationId);
        List<OverpassResponse.Element> all = new java.util.ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            if (i > 0) {
                sleep(properties.callDelay() == null ? Duration.ZERO : properties.callDelay());
            }
            all = merge(all, fetch(queries.get(i), true));
        }
        return all;
    }

    /**
     * Like fetchPlaces(relationId), but district by district: a busy public Overpass server cannot finish a whole
     * province's food query in time (Adana / Balıkesir timed out for hours in Oct 2026) while a district's answers
     * in seconds. Elements on a district border come back twice and are kept once.
     *
     * @param districtRelationIds the city's district relations (OsmAreaImporter wrote them); empty = the city at once
     */
    @Override
    public List<OverpassResponse.Element> fetchPlaces(long relationId, List<Long> districtRelationIds) {
        if (districtRelationIds == null || districtRelationIds.isEmpty()) {
            return fetchPlaces(relationId);
        }
        // Food (thousands of places in a big city) district by district: seconds each. Sights and markets / worship
        // once for the city, one statement per query: a busy server turns away a query by its load at that moment
        // (504 even for "all supermarkets"), so small queries get through and a refused one is retried alone
        long cityArea = areaId(relationId);
        List<OverpassResponse.Element> all = new java.util.ArrayList<>();
        for (long district : districtRelationIds) {
            all = merge(all, fetch(withTimeout(FOOD_QUERY.formatted(areaId(district)), DISTRICT_QUERY_TIMEOUT_SECONDS),
                    false));
            sleep(properties.callDelay() == null ? Duration.ZERO : properties.callDelay());
        }
        for (String query : statementQueries(List.of(SIGHTS_QUERY, MARKETS_WORSHIP_QUERY), cityArea)) {
            all = merge(all, fetch(query, false));
            sleep(properties.callDelay() == null ? Duration.ZERO : properties.callDelay());
        }
        return all;
    }

    static List<OverpassResponse.Element> merge(List<OverpassResponse.Element> first, List<OverpassResponse.Element> second) {
        java.util.Map<String, OverpassResponse.Element> byId = new java.util.LinkedHashMap<>();
        for (List<OverpassResponse.Element> list : List.of(first, second)) {
            list.forEach(e -> byId.putIfAbsent(e.type() + "/" + e.id(), e));
        }
        return new java.util.ArrayList<>(byId.values());
    }

    public List<OverpassResponse.Element> fetchDistricts(long relationId) {
        return fetch(DISTRICTS_QUERY.formatted(areaId(relationId)), true);
    }

    public List<OverpassResponse.Element> fetchAreas(long relationId) {
        // Every province has named neighbourhoods; "none" is a mirror with a missing / stale area index
        return fetch(AREAS_QUERY.formatted(areaId(relationId)), true);
    }

    public List<OverpassResponse.Element> fetchInstitutions(long relationId) {
        // Every province has schools; "none" is a mirror with a missing / stale area index
        return fetch(INSTITUTIONS_QUERY.formatted(areaId(relationId)), true);
    }

    // Inland cities have no coastline: an empty answer is a valid answer here
    public List<OverpassResponse.Element> fetchCoastline(double south, double west, double north, double east) {
        return fetch(COASTLINE_QUERY.formatted(bbox(south, west, north, east)), false);
    }

    static String bbox(double south, double west, double north, double east) {
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", south, west, north, east);
    }

    // The food / drink, the sights and the markets / worship queries of a city
    static List<String> placesQueries(long relationId) {
        long area = areaId(relationId);
        return List.of(FOOD_QUERY.formatted(area), SIGHTS_QUERY.formatted(area), MARKETS_WORSHIP_QUERY.formatted(area));
    }

    // A district's queries end on the server after DISTRICT_QUERY_TIMEOUT_SECONDS: a query we gave up on must not
    // keep one of this IP's few slots busy for minutes (read-timeout must stay longer than this)
    static final int DISTRICT_QUERY_TIMEOUT_SECONDS = 120;

    // The city-wide sights / markets queries end on the server before our read timeout (5 min) gives up on them
    static final int CITY_QUERY_TIMEOUT_SECONDS = 240;

    // Each "nwr[...](area.city);" statement of the queries as a query of its own for the area
    static List<String> statementQueries(List<String> queries, long area) {
        List<String> result = new java.util.ArrayList<>();
        for (String query : queries) {
            for (String line : query.split("\n")) {
                String statement = line.trim();
                if (statement.startsWith("nwr")) {
                    result.add("[out:json][timeout:" + CITY_QUERY_TIMEOUT_SECONDS + "];\narea(id:" + area
                            + ")->.city;\n(\n  " + statement + "\n);\nout center meta;\n");
                }
            }
        }
        return result;
    }

    static String withTimeout(String query, int seconds) {
        return query.replace("[timeout:300]", "[timeout:" + seconds + "]");
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
                    // Read as bytes: some servers (lz4.overpass-api.de) send the JSON as application/octet-stream,
                    // which the String converter refuses; parse() rejects anything that is not Overpass JSON
                    byte[] bytes = restClient.post()
                            .uri(endpoint.trim())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL)
                            .body(form)
                            .retrieve()
                            .body(byte[].class);
                    String body = bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
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

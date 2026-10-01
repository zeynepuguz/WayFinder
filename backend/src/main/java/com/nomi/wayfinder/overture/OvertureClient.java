package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.overture.OvertureMapper.OvertureRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads Overture Maps places (public GeoParquet on S3, no account needed) with an in-process DuckDB: only the
 * food_and_drink places whose bounding box lies in the given area are downloaded (Parquet row group statistics on
 * bbox), ~30-60 s per city. The newest release is named by Overture's STAC catalog ("latest").
 * Licenses: CDLA-Permissive-2.0 (Meta, Microsoft, ...) and Apache-2.0 (Foursquare); attribution is shown in the app.
 */
@Component
public class OvertureClient {

    private static final Logger log = LoggerFactory.getLogger(OvertureClient.class);
    static final String USER_AGENT = "Nomi/1.0 (city guide app)";
    // "2026-09-23.1"
    static final Pattern RELEASE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.\\d+");

    // Point places: bbox.xmin = xmax. names.primary is the local name; phones / websites are the business' own
    private static final String QUERY = """
            SELECT id, names.primary AS name, array_to_string(taxonomy.hierarchy, '/') AS hierarchy,
                   coalesce(confidence, 0) AS confidence, operating_status, phones[1] AS phone,
                   websites[1] AS website, (bbox.ymin + bbox.ymax) / 2 AS lat, (bbox.xmin + bbox.xmax) / 2 AS lon
            FROM read_parquet(?, hive_partitioning = 1)
            WHERE bbox.xmin BETWEEN ? AND ? AND bbox.ymin BETWEEN ? AND ?
              AND (taxonomy.hierarchy[1] = 'food_and_drink'
                   OR list_has_any(taxonomy.hierarchy, ['grocery_store', 'supermarket', 'convenience_store',
                                                        'superstore', 'discount_store', 'shopping_mall',
                                                        'place_of_worship']))
            """;

    private final OvertureProperties properties;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;

    public OvertureClient(OvertureProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(15));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    // The newest release in Overture's catalog
    public String latestRelease() {
        String body = restClient.get().uri(properties.catalogUrl()).retrieve().body(String.class);
        return parseLatest(body, jsonMapper);
    }

    static String parseLatest(String catalogJson, JsonMapper jsonMapper) {
        JsonNode latest = jsonMapper.readTree(catalogJson).path("latest");
        String release = latest.isString() ? latest.asString() : null;
        if (release == null || !RELEASE.matcher(release).matches()) {
            throw new IllegalStateException("Overture catalog has no valid \"latest\" release: " + release);
        }
        return release;
    }

    // Food and drink places of this release inside the box (degrees, WGS84)
    public List<OvertureRow> fetchFood(String release, double south, double west, double north, double east) {
        if (!RELEASE.matcher(release).matches()) {
            throw new IllegalArgumentException("Not an Overture release: " + release);
        }
        long started = System.currentTimeMillis();
        List<OvertureRow> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement setup = connection.createStatement()) {
            setup.execute("INSTALL httpfs");
            setup.execute("LOAD httpfs");
            setup.execute("SET s3_region = 'us-west-2'");
            setup.execute("SET memory_limit = '" + properties.memoryLimit().replaceAll("[^0-9A-Za-z]", "") + "'");
            try (PreparedStatement query = connection.prepareStatement(QUERY)) {
                query.setString(1, properties.dataUrl().formatted(release));
                query.setDouble(2, west);
                query.setDouble(3, east);
                query.setDouble(4, south);
                query.setDouble(5, north);
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        String hierarchy = rs.getString("hierarchy");
                        rows.add(new OvertureRow(rs.getString("id"), rs.getString("name"),
                                hierarchy == null ? List.of() : Arrays.asList(hierarchy.split("/")),
                                rs.getDouble("confidence"), rs.getString("operating_status"), rs.getString("phone"),
                                rs.getString("website"), rs.getDouble("lat"), rs.getDouble("lon")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Overture download failed: " + e.getMessage(), e);
        }
        log.info("Overture: {} food places downloaded ({}) in {} s", rows.size(), release,
                (System.currentTimeMillis() - started) / 1000);
        return rows;
    }
}

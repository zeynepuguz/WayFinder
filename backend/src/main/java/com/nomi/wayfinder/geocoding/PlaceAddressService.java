package com.nomi.wayfinder.geocoding;

import com.nomi.wayfinder.config.HttpClients;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The full address of a place ("Dere Mah., Taşhan Sokak, 05100 Merkez/Amasya"). Few places have a street address in
 * their own data, so it is looked up once from OpenStreetMap's Nominatim (reverse geocoding by the place's
 * coordinates) and stored. Nominatim's usage policy: at most one request a second, an identifying User-Agent, results
 * kept instead of asked again.
 */
@Service
public class PlaceAddressService {

    private static final Logger log = LoggerFactory.getLogger(PlaceAddressService.class);
    // A lookup that found nothing is not repeated before this
    static final Duration RETRY_AFTER = Duration.ofDays(30);
    static final long MIN_INTERVAL_MILLIS = 1100;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;
    private final String baseUrl;
    private final String email;
    private final RestClient restClient;
    private long lastRequestAt;

    public PlaceAddressService(JdbcTemplate jdbc, Clock clock,
                               @Value("${nomi.geocoding.enabled:true}") boolean enabled,
                               @Value("${nomi.geocoding.url:https://nominatim.openstreetmap.org}") String baseUrl,
                               @Value("${nomi.geocoding.email:}") String email) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.email = email;
        this.restClient = HttpClients.restClient(Duration.ofSeconds(3), Duration.ofSeconds(5))
                .defaultHeader("User-Agent", "Nomi city guide (place addresses)")
                .build();
    }

    private record Row(String address, String geoAddress, Instant checkedAt, double latitude, double longitude) {
    }

    /** The place's street address, else the stored lookup, else a new lookup (stored); null when none is known. */
    public String address(long placeId) {
        Row row = jdbc.query("""
                        SELECT address, geo_address, geo_address_checked_at,
                               ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon
                        FROM places WHERE id = ? AND NOT hidden
                        """,
                (rs, i) -> new Row(rs.getString("address"), rs.getString("geo_address"),
                        rs.getTimestamp("geo_address_checked_at") == null ? null
                                : rs.getTimestamp("geo_address_checked_at").toInstant(),
                        rs.getDouble("lat"), rs.getDouble("lon")), placeId)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Place not found: " + placeId));
        if (row.address() != null && !row.address().isBlank()) {
            return row.address();
        }
        if (row.geoAddress() != null) {
            return row.geoAddress();
        }
        Instant now = clock.instant();
        if (!enabled || (row.checkedAt() != null && row.checkedAt().plus(RETRY_AFTER).isAfter(now))) {
            return null;
        }
        String found = lookUp(row.latitude(), row.longitude());
        jdbc.update("UPDATE places SET geo_address = ?, geo_address_checked_at = ? WHERE id = ?",
                found, Timestamp.from(now), placeId);
        return found;
    }

    @SuppressWarnings("unchecked")
    private String lookUp(double latitude, double longitude) {
        try {
            waitForTurn();
            Map<String, Object> body = restClient.get()
                    .uri(baseUrl + "/reverse?format=jsonv2&addressdetails=1&zoom=18&accept-language=tr&lat={lat}&lon={lon}"
                            + (email.isBlank() ? "" : "&email={email}"), latitude, longitude, email)
                    .retrieve()
                    .body(Map.class);
            Object address = body == null ? null : body.get("address");
            return address instanceof Map<?, ?> parts ? format((Map<String, Object>) parts) : null;
        } catch (RuntimeException e) {
            // No address this time: the page shows district and city, the next lookup is a month away
            log.warn("Address lookup failed: {}", e.getMessage());
            return null;
        }
    }

    // Nominatim allows one request a second (from all users of this server together)
    private synchronized void waitForTurn() {
        long wait = lastRequestAt + MIN_INTERVAL_MILLIS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    /**
     * Nominatim's address parts the Turkish way: "Dere Mah., Taşhan Sokak No:5, 05100 Merkez/Amasya". null when
     * neither the street nor the neighbourhood is known (district and city alone are no full address).
     */
    static String format(Map<String, Object> parts) {
        String neighbourhood = first(parts, "neighbourhood", "quarter", "suburb", "village", "hamlet");
        String road = first(parts, "road", "pedestrian", "footway", "square");
        if (neighbourhood == null && road == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (neighbourhood != null) {
            String lower = neighbourhood.toLowerCase(Locale.ROOT);
            lines.add(lower.endsWith("mahallesi") || lower.endsWith("mah.") || lower.endsWith("köyü")
                    ? neighbourhood : neighbourhood + " Mah.");
        }
        if (road != null) {
            String number = first(parts, "house_number");
            lines.add(number == null ? road : road + " No:" + number);
        }
        String province = first(parts, "province", "state", "city");
        String county = first(parts, "county", "town", "city_district");
        String district = county == null || county.equalsIgnoreCase(province) ? "Merkez" : county;
        String postcode = first(parts, "postcode");
        if (province != null) {
            lines.add((postcode == null ? "" : postcode + " ") + district + "/" + province);
        }
        return String.join(", ", lines);
    }

    private static String first(Map<String, Object> parts, String... keys) {
        for (String key : keys) {
            if (parts.get(key) instanceof String value && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

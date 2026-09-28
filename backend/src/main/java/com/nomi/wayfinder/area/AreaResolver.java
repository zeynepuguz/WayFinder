package com.nomi.wayfinder.area;

import com.nomi.wayfinder.osm.OsmAreasImportedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The assistant's "where": which city, district or neighbourhood a message (or the AI service's area text) names.
 * Keeps the cities, districts and neighbourhoods of every imported city in memory (tens of thousands of names,
 * indexed by AreaMatcher); reloads after every city import, and retries a failed / empty load at most once a minute.
 * Cities whose places are not imported yet are left out: naming one keeps the old behaviour (route at the user's
 * position) instead of starting a route where there is nothing to plan with.
 */
@Component
public class AreaResolver {

    private static final Logger log = LoggerFactory.getLogger(AreaResolver.class);
    private static final Duration RETRY_EMPTY = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final CityService cityService;
    private final Clock clock;
    private volatile AreaMatcher matcher;
    private volatile Instant loadedAt;

    public AreaResolver(JdbcTemplate jdbc, CityService cityService, Clock clock) {
        this.jdbc = jdbc;
        this.cityService = cityService;
        this.clock = clock;
    }

    /**
     * @param areaText the AI service's area field (may be null); tried first
     * @param message  the user's message; searched when areaText is missing or unknown
     */
    public Optional<NamedArea> resolve(String areaText, String message) {
        return resolve(areaText, message, null, null);
    }

    /**
     * @param latitude the user's position (may be null); names used in several cities resolve to the city it is in
     */
    public Optional<NamedArea> resolve(String areaText, String message, Double latitude, Double longitude) {
        AreaMatcher current = matcher();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String currentCity = currentCity(latitude, longitude);
        Optional<NamedArea> fromAi = current.find(areaText, currentCity);
        if (fromAi.isPresent() && fromAi.get().kind() != NamedArea.Kind.CITY) {
            return fromAi;
        }
        // The AI's area may be the city only ("Ankara") while the message also names the neighbourhood
        Optional<NamedArea> fromMessage = current.find(message, currentCity);
        if (fromAi.isPresent() && (fromMessage.isEmpty()
                || !sameCity(fromAi.get(), fromMessage.get()))) {
            return fromAi;
        }
        return fromMessage;
    }

    private static boolean sameCity(NamedArea a, NamedArea b) {
        return a.city() != null && a.city().equals(b.city());
    }

    private String currentCity(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        try {
            return cityService.findCityAt(latitude, longitude).map(CityService.City::name).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not find the city at the user's position: {}", e.getMessage());
            return null;
        }
    }

    @EventListener
    public void onAreasImported(OsmAreasImportedEvent event) {
        matcher = null;
    }

    private AreaMatcher matcher() {
        AreaMatcher current = matcher;
        Instant now = clock.instant();
        if (current != null && !(current.isEmpty() && loadedAt.plus(RETRY_EMPTY).isBefore(now))) {
            return current;
        }
        synchronized (this) {
            if (matcher == null || matcher == current) {
                matcher = load();
                loadedAt = now;
            }
            return matcher;
        }
    }

    private AreaMatcher load() {
        try {
            List<NamedArea> areas = new ArrayList<>(jdbc.query("""
                            SELECT name, label_lat, label_lon FROM cities WHERE places_imported_at IS NOT NULL
                            """,
                    (rs, i) -> new NamedArea(rs.getString("name"), NamedArea.Kind.CITY,
                            rs.getDouble("label_lat"), rs.getDouble("label_lon"), null, rs.getString("name"))));
            areas.addAll(jdbc.query("""
                            SELECT d.name, d.label_lat, d.label_lon, c.name AS city
                            FROM districts d JOIN cities c ON c.id = d.city_id
                            WHERE c.places_imported_at IS NOT NULL
                            """,
                    (rs, i) -> new NamedArea(rs.getString("name"), NamedArea.Kind.DISTRICT,
                            rs.getDouble("label_lat"), rs.getDouble("label_lon"), rs.getString("name"),
                            rs.getString("city"))));
            areas.addAll(jdbc.query("""
                            SELECT a.name, a.lat, a.lon, d.name AS district, c.name AS city
                            FROM areas a
                            JOIN cities c ON c.id = a.city_id
                            LEFT JOIN districts d ON d.id = a.district_id
                            WHERE c.places_imported_at IS NOT NULL
                            ORDER BY a.id
                            """,
                    (rs, i) -> new NamedArea(rs.getString("name"), NamedArea.Kind.AREA,
                            rs.getDouble("lat"), rs.getDouble("lon"), rs.getString("district"),
                            rs.getString("city"))));
            AreaMatcher loaded = new AreaMatcher(areas);
            log.info("Assistant places: {} city / district / neighbourhood names usable ({} loaded)",
                    loaded.size(), areas.size());
            return loaded;
        } catch (RuntimeException e) {
            log.warn("Could not load cities / districts / neighbourhoods: {}", e.getMessage());
            return new AreaMatcher(List.of());
        }
    }
}

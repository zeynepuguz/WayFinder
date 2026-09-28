package com.nomi.wayfinder.area;

import com.nomi.wayfinder.dto.CityResponse;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// Cities (provinces) are written by OsmCityImporter (JDBC, PostGIS geometry), so they are read with JDBC too
@Service
public class CityService {

    // Default for requests that name no city (the app covered only Istanbul before)
    public static final String DEFAULT_CITY = "istanbul";
    private static final Collator TURKISH = Collator.getInstance(Locale.forLanguageTag("tr-TR"));
    // A point just outside every polygon (a ferry, a pier, a coastline drawn a little inland): nearest city within ~5 km
    static final double NEAREST_CITY_DEGREES = 0.05;

    private static final String SELECT_RESPONSE = """
            SELECT c.slug, c.name, c.label_lat, c.label_lon, c.south, c.west, c.north, c.east,
                   (SELECT count(*) FROM places p WHERE p.city_id = c.id AND NOT p.hidden) AS place_count,
                   (SELECT count(*) FROM districts d WHERE d.city_id = c.id) AS district_count
            FROM cities c
            """;

    private static final RowMapper<CityResponse> RESPONSE = (rs, i) -> new CityResponse(
            rs.getString("slug"),
            rs.getString("name"),
            rs.getDouble("label_lat"),
            rs.getDouble("label_lon"),
            rs.getObject("south", Double.class),
            rs.getObject("west", Double.class),
            rs.getObject("north", Double.class),
            rs.getObject("east", Double.class),
            rs.getInt("place_count"),
            rs.getInt("district_count"));

    private static final RowMapper<City> CITY = (rs, i) -> new City(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getDouble("label_lat"),
            rs.getDouble("label_lon"));

    private final JdbcTemplate jdbc;

    public CityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Sorted the Turkish way: "Çanakkale" after "Bursa", "İzmir" after "Isparta", "Şanlıurfa" after "Sivas"
    @Transactional(readOnly = true)
    public List<CityResponse> listCities() {
        return sortByName(jdbc.query(SELECT_RESPONSE, RESPONSE));
    }

    // The city containing the point, or the nearest one within NEAREST_CITY_DEGREES
    @Transactional(readOnly = true)
    public Optional<CityResponse> findAt(double latitude, double longitude) {
        return findIdAt(latitude, longitude).flatMap(id ->
                jdbc.query(SELECT_RESPONSE + " WHERE c.id = ?", RESPONSE, id).stream().findFirst());
    }

    public Optional<City> findCityAt(double latitude, double longitude) {
        return findIdAt(latitude, longitude).flatMap(id ->
                jdbc.query("SELECT id, slug, name, label_lat, label_lon FROM cities WHERE id = ?", CITY, id)
                        .stream().findFirst());
    }

    private Optional<Long> findIdAt(double latitude, double longitude) {
        Optional<Long> inside = jdbc.query("""
                        SELECT id FROM cities
                        WHERE geom IS NOT NULL AND ST_Intersects(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                        ORDER BY id LIMIT 1
                        """,
                (rs, i) -> rs.getLong("id"), longitude, latitude).stream().findFirst();
        if (inside.isPresent()) {
            return inside;
        }
        return jdbc.query("""
                        SELECT id FROM cities
                        WHERE geom IS NOT NULL AND ST_DWithin(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?)
                        ORDER BY ST_Distance(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326)) LIMIT 1
                        """,
                (rs, i) -> rs.getLong("id"), longitude, latitude, NEAREST_CITY_DEGREES, longitude, latitude)
                .stream().findFirst();
    }

    public Optional<City> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT id, slug, name, label_lat, label_lon FROM cities WHERE slug = ?", CITY,
                slug.trim().toLowerCase(Locale.ROOT)).stream().findFirst();
    }

    // 404 for an unknown slug; null / blank = the default city (Istanbul)
    public City requireBySlugOrDefault(String slug) {
        String wanted = slug == null || slug.isBlank() ? DEFAULT_CITY : slug;
        return findBySlug(wanted).orElseThrow(() -> new ResourceNotFoundException("City not found: " + wanted));
    }

    static List<CityResponse> sortByName(List<CityResponse> cities) {
        // Collator is not thread safe
        Collator collator = (Collator) TURKISH.clone();
        return cities.stream().sorted(Comparator.comparing(CityResponse::name, collator)).toList();
    }

    /**
     * @param labelLatitude where a route "in <city>" starts (OSM admin centre, else a point inside the city)
     */
    public record City(long id, String slug, String name, double labelLatitude, double labelLongitude) {
    }
}

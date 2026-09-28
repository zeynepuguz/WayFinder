package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.planning.PopularRouteBuilder;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Area;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// What popular routes read besides Place entities (JDBC: popularity columns, areas, names around a group)
@Repository
public class PopularSightRepository {

    // Visible sights of the city (or district) with any real signal of interest, most popular first
    private static final String SEEDS = """
            SELECT p.id, p.popularity, p.source <> 'OSM' AS verified, p.rating, p.image_url IS NOT NULL AS has_image
            FROM places p
            WHERE NOT p.hidden AND p.city_id = ?
              AND (CAST(? AS bigint) IS NULL OR p.district_id = ?)
              AND p.category IN ('ATTRACTION', 'MUSEUM', 'PARK', 'CULTURE')
              AND (p.popularity > 0 OR p.source <> 'OSM' OR p.rating IS NOT NULL OR p.image_url IS NOT NULL)
            ORDER BY p.popularity DESC NULLS LAST, (p.source <> 'OSM') DESC, p.rating DESC NULLS LAST, p.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public PopularSightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Place id -> seed score: its popularity, or PopularRouteBuilder.fallbackScore when that is higher / unknown.
     *
     * @param districtId null = the whole city
     */
    public Map<Long, Double> seedScores(long cityId, Long districtId, int limit) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        jdbc.query(SEEDS, rs -> {
            Double popularity = rs.getObject("popularity", Double.class);
            double fallback = PopularRouteBuilder.fallbackScore(rs.getBoolean("verified"),
                    rs.getObject("rating", Double.class), rs.getBoolean("has_image"));
            scores.put(rs.getLong("id"), Math.max(popularity == null ? 0 : popularity, fallback));
        }, cityId, districtId, districtId, limit);
        return scores;
    }

    // Named areas (quarters, neighbourhoods) of the city inside the box
    public List<Area> areas(long cityId, double south, double west, double north, double east) {
        return jdbc.query("""
                        SELECT name, kind, lat, lon FROM areas
                        WHERE city_id = ? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                        """,
                (rs, i) -> new Area(rs.getString("name"), rs.getString("kind"), rs.getDouble("lat"), rs.getDouble("lon")),
                cityId, south, north, west, east);
    }

    // Names of the visible places inside the box
    public List<String> placeNames(double south, double west, double north, double east) {
        return jdbc.queryForList("""
                SELECT name FROM places
                WHERE NOT hidden AND location && CAST(ST_MakeEnvelope(?, ?, ?, ?, 4326) AS geography)
                LIMIT 3000
                """, String.class, west, south, east, north);
    }
}

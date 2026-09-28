package com.nomi.wayfinder.area;

import com.nomi.wayfinder.dto.DistrictResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// Districts are written by OsmAreaImporter (JDBC, PostGIS geometry), so they are read with JDBC too (no entity)
@Service
public class DistrictService {

    private static final Collator TURKISH = Collator.getInstance(Locale.forLanguageTag("tr-TR"));

    private final JdbcTemplate jdbc;
    private final CityService cityService;

    public DistrictService(JdbcTemplate jdbc, CityService cityService) {
        this.jdbc = jdbc;
        this.cityService = cityService;
    }

    /**
     * A city's districts, sorted the Turkish way: "Çatalca" after "Büyükçekmece", "Şişli" after "Sultangazi".
     *
     * @param citySlug null / blank = Istanbul (the only city before); unknown slug = 404
     */
    @Transactional(readOnly = true)
    public List<DistrictResponse> listDistricts(String citySlug) {
        long cityId = cityService.requireBySlugOrDefault(citySlug).id();
        List<DistrictResponse> districts = jdbc.query("""
                        SELECT d.slug, d.name, d.label_lat, d.label_lon, d.south, d.west, d.north, d.east,
                               (SELECT count(*) FROM places p WHERE p.district_id = d.id) AS place_count
                        FROM districts d
                        WHERE d.city_id = ?
                        """,
                (rs, i) -> new DistrictResponse(
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getDouble("label_lat"),
                        rs.getDouble("label_lon"),
                        rs.getObject("south", Double.class),
                        rs.getObject("west", Double.class),
                        rs.getObject("north", Double.class),
                        rs.getObject("east", Double.class),
                        rs.getInt("place_count")),
                cityId);
        return sortByName(districts);
    }

    static List<DistrictResponse> sortByName(List<DistrictResponse> districts) {
        // Collator is not thread safe
        Collator collator = (Collator) TURKISH.clone();
        return districts.stream().sorted(Comparator.comparing(DistrictResponse::name, collator)).toList();
    }

    // District slugs are unique per city only ("merkez" exists in many cities)
    public Optional<Long> findIdBySlug(long cityId, String slug) {
        return findBySlug(cityId, slug).map(District::id);
    }

    public Optional<District> findBySlug(long cityId, String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT id, name, label_lat, label_lon FROM districts WHERE city_id = ? AND slug = ?",
                (rs, i) -> new District(rs.getLong("id"), rs.getString("name"),
                        rs.getDouble("label_lat"), rs.getDouble("label_lon")),
                cityId, slug.trim().toLowerCase(Locale.ROOT)).stream().findFirst();
    }

    /**
     * @param labelLatitude where a route "in <district>" starts (OSM label / admin centre, else a point inside it)
     */
    public record District(long id, String name, double labelLatitude, double labelLongitude) {
    }
}

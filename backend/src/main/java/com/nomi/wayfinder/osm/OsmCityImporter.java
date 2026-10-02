package com.nomi.wayfinder.osm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turkey's cities (provinces, admin_level=4) and the per-city import pipeline:
 * districts + neighbourhoods (OsmAreaImporter), then places (OsmPlaceImporter, which also assigns every place its
 * city and district), then the city is marked imported and listeners run (assistant names reload, place photos).
 *
 * Province polygons are built in PostGIS like district polygons (OsmAreaImporter#SHAPE_CTE). The label point,
 * where a route "in Ankara" starts, is the relation's admin_centre node (the province capital's centre) when OSM
 * has one, else its label node, else a point inside the polygon. Rows are upserted by osm_id; a city keeps its
 * old polygon when the new ways do not form one. Runs are serialized by OsmImportJobs.
 */
@Service
public class OsmCityImporter {

    private static final Logger log = LoggerFactory.getLogger(OsmCityImporter.class);

    public static final int EXPECTED_CITIES = 81;
    // Fewer provinces than this in an answer means a partial / broken answer: nothing is written
    static final int MIN_CITIES = 70;

    private static final String UPSERT_CITY = OsmAreaImporter.SHAPE_CTE + """
            INSERT INTO cities (osm_id, name, slug, iso_code, population, geom, label_lat, label_lon,
                                south, west, north, east, updated_at)
            SELECT ?, ?, ?, ?, ?, g,
                   COALESCE(?, ST_Y(ST_PointOnSurface(g)), ?),
                   COALESCE(?, ST_X(ST_PointOnSurface(g)), ?),
                   ST_YMin(g), ST_XMin(g), ST_YMax(g), ST_XMax(g), now()
            FROM final
            ON CONFLICT (osm_id) DO UPDATE SET
                name = EXCLUDED.name,
                slug = EXCLUDED.slug,
                iso_code = EXCLUDED.iso_code,
                population = EXCLUDED.population,
                geom = COALESCE(EXCLUDED.geom, cities.geom),
                label_lat = EXCLUDED.label_lat,
                label_lon = EXCLUDED.label_lon,
                south = COALESCE(EXCLUDED.south, cities.south),
                west = COALESCE(EXCLUDED.west, cities.west),
                north = COALESCE(EXCLUDED.north, cities.north),
                east = COALESCE(EXCLUDED.east, cities.east),
                updated_at = now()
            """;

    private final OsmSource overpassClient;
    private final OsmAreaImporter areaImporter;
    private final OsmPlaceImporter placeImporter;
    private final OsmContextImporter contextImporter;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;

    public OsmCityImporter(OsmSource overpassClient, OsmAreaImporter areaImporter, OsmPlaceImporter placeImporter,
                           OsmContextImporter contextImporter, JdbcTemplate jdbc, TransactionTemplate transactions,
                           ApplicationEventPublisher events) {
        this.contextImporter = contextImporter;
        this.overpassClient = overpassClient;
        this.areaImporter = areaImporter;
        this.placeImporter = placeImporter;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.events = events;
    }

    public int cityCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM cities", Integer.class);
        return n == null ? 0 : n;
    }

    // Downloads the 81 provinces and upserts them (polygons, label points, ISO codes)
    public ProvinceImportResult importProvinces() {
        long started = System.currentTimeMillis();
        log.info("City import: downloading Turkey's provinces from Overpass...");
        List<OverpassResponse.Element> relations = overpassClient.fetchProvinces();

        List<ProvinceInput> provinces = new ArrayList<>();
        int foreign = 0;
        for (OverpassResponse.Element relation : relations) {
            ProvinceInput province = toProvince(relation);
            if (province == null) {
                foreign++;
            } else {
                provinces.add(province);
            }
        }
        if (provinces.size() < MIN_CITIES) {
            throw new IllegalStateException("Overpass returned only " + provinces.size() + " Turkish provinces");
        }

        for (ProvinceInput province : provinces) {
            Object[] ways = province.boundary().wayWkts().toArray();
            transactions.executeWithoutResult(status -> {
                // A re-created relation (new osm_id) keeps its row, and with it every district / place reference
                jdbc.update("""
                        UPDATE cities SET osm_id = ?
                        WHERE slug = ? AND osm_id <> ? AND NOT EXISTS (SELECT 1 FROM cities c2 WHERE c2.osm_id = ?)
                        """, province.boundary().osmId(), province.boundary().slug(), province.boundary().osmId(),
                        province.boundary().osmId());
                jdbc.update(con -> {
                    var ps = con.prepareStatement(UPSERT_CITY);
                    ps.setArray(1, con.createArrayOf("text", ways));
                    ps.setString(2, province.boundary().osmId());
                    ps.setString(3, province.boundary().name());
                    ps.setString(4, province.boundary().slug());
                    ps.setString(5, province.isoCode());
                    if (province.population() == null) {
                        ps.setNull(6, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(6, province.population());
                    }
                    setNullableDouble(ps, 7, province.labelLat());
                    ps.setDouble(8, province.boundary().fallbackLat());
                    setNullableDouble(ps, 9, province.labelLon());
                    ps.setDouble(10, province.boundary().fallbackLon());
                    return ps;
                });
            });
        }

        int total = cityCount();
        int withoutArea = count("SELECT count(*) FROM cities WHERE geom IS NULL OR ST_IsEmpty(geom) OR NOT ST_IsValid(geom)");
        if (total != EXPECTED_CITIES) {
            log.warn("City import: {} cities in the database, expected {}", total, EXPECTED_CITIES);
        }
        if (withoutArea > 0) {
            log.warn("City import: {} cities have no valid polygon: {}", withoutArea, jdbc.queryForList(
                    "SELECT name FROM cities WHERE geom IS NULL OR ST_IsEmpty(geom) OR NOT ST_IsValid(geom)",
                    String.class));
        }
        ProvinceImportResult result = new ProvinceImportResult(relations.size(), provinces.size(), foreign, total,
                withoutArea);
        log.info("City import finished in {} s: {}", (System.currentTimeMillis() - started) / 1000, result);
        return result;
    }

    /**
     * Districts + neighbourhoods, then places of one city. The city counts as imported (places_imported_at) only
     * when both its districts and its places were written.
     *
     * @param pauseBetweenCalls the polite delay between two Overpass calls
     */
    public CityImportResult importCity(OsmCity city, Runnable pauseBetweenCalls) {
        long started = System.currentTimeMillis();
        OsmAreaImporter.AreaImportResult areas = null;
        String districtError = null;
        try {
            areas = areaImporter.importCity(city, pauseBetweenCalls);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // Shutting down (or the run was stopped): do not go on with the places
                throw e;
            }
            // Places are still useful without districts; the city stays "not imported" so the next run tries again
            districtError = e.getMessage();
            log.warn("OSM import ({}): district import failed, continuing with places: {}", city.name(), e.getMessage());
        }

        pauseBetweenCalls.run();
        OsmPlaceImporter.ImportResult places = placeImporter.importCity(city);
        if (areas != null) {
            areas = areas.withPlaces(new OsmAreaImporter.PlaceDistricts(places.placesWithDistrict(),
                    places.placesInCity() - places.placesWithDistrict()));
        }
        places = places.withAreas(areas);

        // Institution areas and the coastline: route realism (campus cafés, "deniz"). Their failure (busy Overpass)
        // keeps the previous data and does not fail the city
        OsmContextImporter.ContextImportResult context = null;
        try {
            pauseBetweenCalls.run();
            context = contextImporter.importCity(city, pauseBetweenCalls);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            log.warn("OSM import ({}): institution / coastline import failed, continuing: {}", city.name(), e.getMessage());
        }

        if (districtError == null) {
            jdbc.update("UPDATE cities SET places_imported_at = now() WHERE id = ?", city.id());
        }
        // The assistant reloads its names; place photos are looked up for new / changed references
        events.publishEvent(new OsmAreasImportedEvent(areas));
        events.publishEvent(new OsmImportFinishedEvent(places));

        long seconds = (System.currentTimeMillis() - started) / 1000;
        log.info("OSM import ({}) finished in {} s: {} places, {} districts", city.name(), seconds,
                places.placesInCity(), areas == null ? "?" : areas.districts());
        return new CityImportResult(places, districtError, seconds, context);
    }

    // Every city row, for the job to pick from
    public List<CityRow> listCities() {
        return jdbc.query("""
                        SELECT id, slug, name, osm_id, population, places_imported_at FROM cities
                        """,
                (rs, i) -> new CityRow(
                        rs.getLong("id"),
                        rs.getString("slug"),
                        rs.getString("name"),
                        relationId(rs.getString("osm_id")),
                        rs.getObject("population", Long.class),
                        rs.getObject("places_imported_at", java.time.OffsetDateTime.class) == null ? null
                                : rs.getObject("places_imported_at", java.time.OffsetDateTime.class).toInstant()))
                .stream().filter(c -> c.relationId() > 0).toList();
    }

    public Optional<CityRow> findCity(String slug) {
        String wanted = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        return listCities().stream().filter(c -> c.slug().equals(wanted)).findFirst();
    }

    private static void setNullableDouble(java.sql.PreparedStatement ps, int index, Double value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    // ---------- pure mapping (unit tested) ----------

    // "relation/223474" -> 223474; 0 when it is not a relation
    static long relationId(String osmId) {
        if (osmId == null || !osmId.startsWith("relation/")) {
            return 0;
        }
        try {
            return Long.parseLong(osmId.substring("relation/".length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * @return null for relations that are not a Turkish province (neighbouring countries' regions that share a
     * border way have no "TR-" ISO3166-2 code) or have no name / way geometry
     */
    static ProvinceInput toProvince(OverpassResponse.Element relation) {
        String iso = OsmAreaImporter.trimToNull(relation.tag("ISO3166-2"));
        if (iso == null || !iso.toUpperCase(Locale.ROOT).startsWith("TR-")) {
            return null;
        }
        OsmAreaImporter.DistrictInput boundary = OsmAreaImporter.toDistrict(relation);
        if (boundary == null) {
            return null;
        }
        // The province capital's centre (admin_centre) is where a city trip starts; the label node is only a
        // rendering hint for map labels
        Double lat = boundary.centreLat() != null ? boundary.centreLat() : boundary.labelLat();
        Double lon = boundary.centreLon() != null ? boundary.centreLon() : boundary.labelLon();
        return new ProvinceInput(boundary, iso.toUpperCase(Locale.ROOT), population(relation.tag("population")),
                lat, lon);
    }

    // "5803482" -> 5803482; "5.803.482" / "5 803 482" -> 5803482; anything else -> null
    static Long population(String tag) {
        if (tag == null) {
            return null;
        }
        String digits = tag.trim().replaceAll("[\\s.,]", "");
        if (!digits.matches("\\d{1,12}")) {
            return null;
        }
        return Long.parseLong(digits);
    }

    /**
     * @param labelLat admin_centre node, else label node; null = a point inside the polygon
     */
    record ProvinceInput(OsmAreaImporter.DistrictInput boundary, String isoCode, Long population,
                         Double labelLat, Double labelLon) {
    }

    /**
     * @param fetched        relations Overpass returned
     * @param provinces      Turkish provinces among them
     * @param foreign        relations left out (other countries, no name / geometry)
     * @param cities         cities in the database afterwards (81)
     * @param withoutPolygon cities whose ways did not form a valid polygon (should be 0)
     */
    public record ProvinceImportResult(int fetched, int provinces, int foreign, int cities, int withoutPolygon) {
    }

    /**
     * @param districtError why the district download failed (the city then stays "not imported"); null = fine
     * @param context       institution areas + coastline of the city; null when that step failed
     */
    public record CityImportResult(OsmPlaceImporter.ImportResult result, String districtError, long seconds,
                                   OsmContextImporter.ContextImportResult context) {
    }

    /**
     * @param placesImportedAt last complete import; null = never
     */
    public record CityRow(long id, String slug, String name, long relationId, Long population,
                          java.time.Instant placesImportedAt) {

        public OsmCity toOsmCity() {
            return new OsmCity(id, slug, name, relationId);
        }
    }
}

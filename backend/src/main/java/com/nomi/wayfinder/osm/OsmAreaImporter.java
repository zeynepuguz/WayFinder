package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.i18n.TurkishFold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Imports one city's districts (ilçe boundaries, admin_level=6) and named neighbourhoods (place=suburb /
 * quarter / neighbourhood nodes) from OpenStreetMap, then writes the district_id of the city's places.
 * OsmCityImporter runs it for every city; the city (province) rows themselves come from importProvinces there.
 *
 * District polygons are assembled in PostGIS from the relation's member ways (ST_BuildArea, noding the lines
 * first when that is needed, then ST_MakeValid + ST_Multi). The label point, where a route "in Üsküdar"
 * starts, is the relation's label / admin_centre node when OSM has one, else a point inside the polygon.
 * The area query also returns districts of neighbouring cities that share a border way: a district is kept only
 * when a point inside it lies in the city's polygon.
 * Rows are upserted by osm_id; nothing is deleted when OSM leaves something out (a partial answer must not
 * wipe districts).
 */
@Service
public class OsmAreaImporter {

    private static final Logger log = LoggerFactory.getLogger(OsmAreaImporter.class);

    // Places just outside every polygon (piers, a coastline drawn a little inland): nearest district within ~1 km
    static final double NEAREST_DISTRICT_DEGREES = 0.01;

    // Builds the polygon from the member ways ("lines") into "final(g)"; parameter 1 = the ways as WKT text[]
    static final String SHAPE_CTE = """
            WITH lines AS (
                SELECT ST_GeomFromText(w, 4326) AS g FROM unnest(CAST(? AS text[])) AS w
            ), built AS (
                SELECT ST_BuildArea(ST_Collect(g)) AS a FROM lines
            ), area AS (
                -- Ways that cross instead of meeting at their ends only form an area after noding
                SELECT CASE WHEN a IS NULL OR ST_IsEmpty(a)
                            THEN (SELECT ST_BuildArea(ST_Node(ST_Collect(g))) FROM lines)
                            ELSE a END AS a
                FROM built
            ), shape AS (
                SELECT ST_Multi(ST_CollectionExtract(ST_MakeValid(a), 3)) AS g FROM area
            ), final AS (
                SELECT CASE WHEN g IS NULL OR ST_IsEmpty(g) THEN NULL ELSE g END AS g FROM shape
            )
            """;

    // true when the district belongs to the city: no polygon to test, the city has no polygon, or a point inside
    // the district lies inside the city
    private static final String DISTRICT_IN_CITY = SHAPE_CTE + """
            SELECT f.g IS NULL OR c.geom IS NULL OR ST_Intersects(c.geom, ST_PointOnSurface(f.g))
            FROM final f LEFT JOIN cities c ON c.id = ?
            """;

    private static final String UPSERT_DISTRICT = SHAPE_CTE + """
            INSERT INTO districts (osm_id, name, slug, geom, label_lat, label_lon, south, west, north, east,
                                   city_id, updated_at)
            SELECT ?, ?, ?, g,
                   COALESCE(?, ST_Y(ST_PointOnSurface(g)), ?),
                   COALESCE(?, ST_X(ST_PointOnSurface(g)), ?),
                   ST_YMin(g), ST_XMin(g), ST_YMax(g), ST_XMax(g), ?, now()
            FROM final
            ON CONFLICT (osm_id) DO UPDATE SET
                name = EXCLUDED.name,
                slug = EXCLUDED.slug,
                geom = EXCLUDED.geom,
                label_lat = EXCLUDED.label_lat,
                label_lon = EXCLUDED.label_lon,
                south = EXCLUDED.south,
                west = EXCLUDED.west,
                north = EXCLUDED.north,
                east = EXCLUDED.east,
                city_id = EXCLUDED.city_id,
                updated_at = now()
            """;

    private static final String UPSERT_AREA = """
            INSERT INTO areas (osm_id, name, slug, kind, lat, lon, district_id, city_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?,
                    (SELECT d.id FROM districts d
                     WHERE d.city_id = ? AND ST_Contains(d.geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                     ORDER BY d.id LIMIT 1),
                    ?, now())
            ON CONFLICT (osm_id) DO UPDATE SET
                name = EXCLUDED.name,
                slug = EXCLUDED.slug,
                kind = EXCLUDED.kind,
                lat = EXCLUDED.lat,
                lon = EXCLUDED.lon,
                district_id = EXCLUDED.district_id,
                city_id = EXCLUDED.city_id,
                updated_at = now()
            """;

    private final OverpassClient overpassClient;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public OsmAreaImporter(OverpassClient overpassClient, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.overpassClient = overpassClient;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    // Downloads the city's districts and neighbourhoods (Overpass calls are separated by the polite delay)
    public AreaImportResult importCity(OsmCity city, Runnable pauseBetweenCalls) {
        long started = System.currentTimeMillis();
        log.info("District import ({}): downloading districts from Overpass...", city.name());
        List<OverpassResponse.Element> relations = overpassClient.fetchDistricts(city.relationId());
        DistrictStats districts = importDistricts(relations, city);

        // Neighbourhoods only refine the assistant's "where"; districts stay usable when this download fails
        pauseBetweenCalls.run();
        try {
            log.info("District import ({}): downloading neighbourhoods from Overpass...", city.name());
            importAreas(overpassClient.fetchAreas(city.relationId()), city);
        } catch (RuntimeException e) {
            log.warn("District import ({}): neighbourhood download failed, keeping the previous ones: {}",
                    city.name(), e.getMessage());
        }
        int areas = count("SELECT count(*) FROM areas WHERE city_id = ?", city.id());
        int areasWithDistrict = count("SELECT count(*) FROM areas WHERE city_id = ? AND district_id IS NOT NULL",
                city.id());

        AreaImportResult result = new AreaImportResult(districts.total(), districts.withoutArea(), areas,
                areasWithDistrict, 0, 0);
        log.info("District import ({}) finished in {} s: {}", city.name(),
                (System.currentTimeMillis() - started) / 1000, result);
        return result;
    }

    DistrictStats importDistricts(List<OverpassResponse.Element> relations, OsmCity city) {
        int skipped = 0;
        int outside = 0;
        for (OverpassResponse.Element relation : relations) {
            DistrictInput district = toDistrict(relation);
            if (district == null) {
                skipped++;
                continue;
            }
            Object[] ways = district.wayWkts().toArray();
            Boolean inCity = jdbc.query(con -> {
                var ps = con.prepareStatement(DISTRICT_IN_CITY);
                ps.setArray(1, con.createArrayOf("text", ways));
                ps.setLong(2, city.id());
                return ps;
            }, rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE);
            if (!Boolean.TRUE.equals(inCity)) {
                // A neighbouring city's district that shares a border way
                outside++;
                continue;
            }
            transactions.executeWithoutResult(status -> {
                // A renamed / re-created relation keeps the slug; the old row would block it
                jdbc.update("DELETE FROM districts WHERE city_id = ? AND slug = ? AND osm_id <> ?",
                        city.id(), district.slug(), district.osmId());
                jdbc.update(con -> {
                    var ps = con.prepareStatement(UPSERT_DISTRICT);
                    ps.setArray(1, con.createArrayOf("text", ways));
                    ps.setString(2, district.osmId());
                    ps.setString(3, district.name());
                    ps.setString(4, district.slug());
                    setNullableDouble(ps, 5, district.labelLat());
                    ps.setDouble(6, district.fallbackLat());
                    setNullableDouble(ps, 7, district.labelLon());
                    ps.setDouble(8, district.fallbackLon());
                    ps.setLong(9, city.id());
                    return ps;
                });
            });
        }

        int total = count("SELECT count(*) FROM districts WHERE city_id = ?", city.id());
        int withoutArea = count("""
                SELECT count(*) FROM districts
                WHERE city_id = ? AND (geom IS NULL OR ST_IsEmpty(geom) OR NOT ST_IsValid(geom))
                """, city.id());
        if (skipped > 0) {
            log.warn("District import ({}): {} relations had no name or no way geometry and were skipped",
                    city.name(), skipped);
        }
        if (withoutArea > 0) {
            log.warn("District import ({}): {} districts have no valid polygon: {}", city.name(), withoutArea,
                    jdbc.queryForList("""
                            SELECT name FROM districts
                            WHERE city_id = ? AND (geom IS NULL OR ST_IsEmpty(geom) OR NOT ST_IsValid(geom))
                            """, String.class, city.id()));
        }
        log.info("District import ({}): {} districts ({} relations downloaded, {} of neighbouring cities left out)",
                city.name(), total, relations.size(), outside);
        return new DistrictStats(total, withoutArea);
    }

    int importAreas(List<OverpassResponse.Element> nodes, OsmCity city) {
        // One row per osm_id (Overpass never repeats one, but ON CONFLICT would fail on a repeat in a batch)
        Map<String, AreaInput> areas = new LinkedHashMap<>();
        for (OverpassResponse.Element node : nodes) {
            AreaInput area = toArea(node);
            if (area != null) {
                areas.putIfAbsent(area.osmId(), area);
            }
        }
        List<AreaInput> rows = new ArrayList<>(areas.values());
        transactions.executeWithoutResult(status ->
                jdbc.batchUpdate(UPSERT_AREA, rows, 500, (ps, a) -> {
                    ps.setString(1, a.osmId());
                    ps.setString(2, a.name());
                    ps.setString(3, a.slug());
                    ps.setString(4, a.kind());
                    ps.setDouble(5, a.latitude());
                    ps.setDouble(6, a.longitude());
                    ps.setLong(7, city.id());
                    ps.setDouble(8, a.longitude());
                    ps.setDouble(9, a.latitude());
                    ps.setLong(10, city.id());
                }));
        log.info("District import ({}): {} neighbourhoods written", city.name(), rows.size());
        return rows.size();
    }

    /**
     * Writes places.district_id of the city's places from its district polygons (verified and OSM places).
     * Run after the city's districts change and after every place import (city_id must be set first).
     */
    public PlaceDistricts assignPlaceDistricts(long cityId) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE places p SET district_id = d.id
                    FROM districts d
                    WHERE p.city_id = ? AND d.city_id = ?
                      AND d.geom IS NOT NULL
                      AND ST_Intersects(d.geom, p.location::geometry)
                      AND p.district_id IS DISTINCT FROM d.id
                    """, cityId, cityId);
            jdbc.update("""
                    UPDATE places p SET district_id = (
                        SELECT d.id FROM districts d
                        WHERE d.city_id = ? AND d.geom IS NOT NULL AND ST_DWithin(d.geom, p.location::geometry, ?)
                        ORDER BY ST_Distance(d.geom, p.location::geometry)
                        LIMIT 1)
                    WHERE p.city_id = ? AND (p.district_id IS NULL
                          OR NOT EXISTS (SELECT 1 FROM districts d2 WHERE d2.id = p.district_id AND d2.city_id = ?))
                    """, cityId, NEAREST_DISTRICT_DEGREES, cityId, cityId);
        });
        PlaceDistricts result = new PlaceDistricts(
                count("SELECT count(*) FROM places WHERE city_id = ? AND district_id IS NOT NULL", cityId),
                count("SELECT count(*) FROM places WHERE city_id = ? AND district_id IS NULL", cityId));
        log.info("District import: {} places of the city have a district, {} have none", result.withDistrict(),
                result.withoutDistrict());
        return result;
    }

    private static void setNullableDouble(java.sql.PreparedStatement ps, int index, Double value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ---------- pure mapping (unit tested) ----------

    /**
     * @return null when the relation has no name or no usable way geometry
     */
    static DistrictInput toDistrict(OverpassResponse.Element relation) {
        String name = trimToNull(relation.tag("name"));
        if (name == null || relation.members() == null) {
            return null;
        }

        List<String> wkts = new ArrayList<>();
        double latSum = 0;
        double lonSum = 0;
        int points = 0;
        Double labelLat = null;
        Double labelLon = null;
        Double centreLat = null;
        Double centreLon = null;

        for (OverpassResponse.Member member : relation.members()) {
            String role = member.role() == null ? "" : member.role();
            if ("way".equals(member.type()) && (role.isEmpty() || role.equals("outer") || role.equals("inner"))) {
                List<OverpassResponse.Center> geometry = member.geometry() == null ? List.of()
                        : member.geometry().stream().filter(p -> p != null && p.lat() != null && p.lon() != null).toList();
                if (geometry.size() < 2) {
                    continue;
                }
                StringBuilder wkt = new StringBuilder("LINESTRING(");
                for (int i = 0; i < geometry.size(); i++) {
                    OverpassResponse.Center p = geometry.get(i);
                    if (i > 0) {
                        wkt.append(',');
                    }
                    wkt.append(String.format(Locale.ROOT, "%.7f %.7f", p.lon(), p.lat()));
                    latSum += p.lat();
                    lonSum += p.lon();
                    points++;
                }
                wkts.add(wkt.append(')').toString());
            } else if ("node".equals(member.type()) && member.lat() != null && member.lon() != null) {
                if (role.equals("label")) {
                    labelLat = member.lat();
                    labelLon = member.lon();
                } else if (role.equals("admin_centre")) {
                    centreLat = member.lat();
                    centreLon = member.lon();
                }
            }
        }
        if (wkts.isEmpty()) {
            return null;
        }

        return new DistrictInput(
                relation.type() + "/" + relation.id(),
                name,
                TurkishFold.slug(name),
                wkts,
                labelLat != null ? labelLat : centreLat,
                labelLon != null ? labelLon : centreLon,
                latSum / points,
                lonSum / points,
                centreLat,
                centreLon);
    }

    static AreaInput toArea(OverpassResponse.Element node) {
        String name = trimToNull(node.tag("name"));
        String kind = trimToNull(node.tag("place"));
        if (name == null || kind == null || node.latitude() == null || node.longitude() == null
                || !Set.of("suburb", "quarter", "neighbourhood").contains(kind)) {
            return null;
        }
        return new AreaInput(node.type() + "/" + node.id(), truncate(name, 255), TurkishFold.slug(name), kind,
                node.latitude(), node.longitude());
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * @param wayWkts     member ways (outer / inner) as WGS84 LINESTRING WKT
     * @param labelLat    label node, else admin_centre node; null = let PostGIS pick a point inside the polygon
     * @param fallbackLat average of the way points, only used when no polygon could be built either
     * @param centreLat   the admin_centre node alone (a province's capital); null = none
     */
    record DistrictInput(String osmId, String name, String slug, List<String> wayWkts,
                         Double labelLat, Double labelLon, double fallbackLat, double fallbackLon,
                         Double centreLat, Double centreLon) {

        DistrictInput(String osmId, String name, String slug, List<String> wayWkts,
                      Double labelLat, Double labelLon, double fallbackLat, double fallbackLon) {
            this(osmId, name, slug, wayWkts, labelLat, labelLon, fallbackLat, fallbackLon, null, null);
        }
    }

    record AreaInput(String osmId, String name, String slug, String kind, double latitude, double longitude) {
    }

    record DistrictStats(int total, int withoutArea) {
    }

    public record PlaceDistricts(int withDistrict, int withoutDistrict) {
    }

    /**
     * @param districts             the city's districts in the database
     * @param districtsWithoutArea  districts whose OSM ways did not give a valid polygon (should be 0)
     * @param areas                 the city's neighbourhoods in the database
     * @param areasWithDistrict     neighbourhoods inside a district polygon
     * @param placesWithDistrict    the city's places (verified + OSM) that have a district (0 until places are assigned)
     * @param placesWithoutDistrict the city's places outside every district (should be very few)
     */
    public record AreaImportResult(int districts, int districtsWithoutArea, int areas, int areasWithDistrict,
                                   int placesWithDistrict, int placesWithoutDistrict) {

        AreaImportResult withPlaces(PlaceDistricts places) {
            return new AreaImportResult(districts, districtsWithoutArea, areas, areasWithDistrict,
                    places.withDistrict(), places.withoutDistrict());
        }
    }
}

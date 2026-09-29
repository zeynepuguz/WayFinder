package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a city's places lie in or next to, from OpenStreetMap, for realistic routes:
 * - institution areas (amenity=university|college|school|hospital|prison, landuse=military|industrial, military=*):
 *   a café inside a university campus, a hospital or a factory site is not a place a visitor plans a day around.
 *   places.inside_institution = inside such a polygon, except museums / sights / culture venues (a museum on a campus
 *   is fine), places with a Wikidata item and verified places. Those places stay in lists, map, search and on their
 *   own page, but route plans, recommendations and popular routes leave them out.
 * - the sea coastline (natural=coastline): places.near_sea = within NEAR_SEA_METERS of it ("deniz" interest).
 *
 * Polygons are built in PostGIS like district polygons (OsmAreaImporter#SHAPE_CTE); a city's rows are replaced
 * only after a successful download, so a failed / busy Overpass keeps the previous data (and the flags). Runs after
 * every city import (OsmCityImporter); admins can re-run the download or only the flag computation.
 */
@Service
public class OsmContextImporter {

    private static final Logger log = LoggerFactory.getLogger(OsmContextImporter.class);

    static final int NEAR_SEA_METERS = 300;
    // Between the two Overpass calls of an admin run
    static final long ADMIN_CALL_DELAY_MS = 5000;
    // Box around the city for the coastline query (degrees)
    static final double COAST_PADDING = 0.02;
    static final Set<String> INSTITUTION_AMENITIES = Set.of("university", "college", "school", "hospital", "prison");
    static final Set<String> INSTITUTION_LANDUSES = Set.of("military", "industrial");

    private static final String UPSERT_INSTITUTION = OsmAreaImporter.SHAPE_CTE + """
            INSERT INTO institution_areas (city_id, osm_id, kind, name, geom, updated_at)
            SELECT ?, ?, ?, ?, g, now() FROM final WHERE g IS NOT NULL
            ON CONFLICT (city_id, osm_id) DO UPDATE SET
                kind = EXCLUDED.kind, name = EXCLUDED.name, geom = EXCLUDED.geom, updated_at = now()
            """;

    private static final String UPSERT_COASTLINE = """
            INSERT INTO coastlines (city_id, osm_id, geom, updated_at)
            SELECT ?, ?, g, now() FROM (SELECT ST_GeomFromText(?, 4326) AS g) x WHERE ST_IsValid(g)
            ON CONFLICT (city_id, osm_id) DO UPDATE SET geom = EXCLUDED.geom, updated_at = now()
            """;

    // Museums, sights and culture venues on a campus, notable places (Wikidata) and verified places stay plannable
    static final String FLAG_INSIDE_INSTITUTION = """
            UPDATE places p SET inside_institution = x.inside
            FROM (
                SELECT p2.id,
                       (p2.source IN ('OSM', 'OVERTURE') AND p2.wikidata IS NULL
                        AND p2.category NOT IN ('MUSEUM', 'ATTRACTION', 'CULTURE')
                        AND EXISTS (SELECT 1 FROM institution_areas a
                                    WHERE ST_Intersects(a.geom, p2.location::geometry))) AS inside
                FROM places p2 WHERE p2.city_id = ?
            ) x
            WHERE p.id = x.id AND p.inside_institution <> x.inside
            """;

    static final String FLAG_NEAR_SEA = """
            UPDATE places p SET near_sea = x.near
            FROM (
                SELECT p2.id,
                       EXISTS (SELECT 1 FROM coastlines c
                               WHERE ST_DWithin(CAST(c.geom AS geography), p2.location, ?)) AS near
                FROM places p2 WHERE p2.city_id = ?
            ) x
            WHERE p.id = x.id AND p.near_sea <> x.near
            """;

    private final OverpassClient overpassClient;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OsmContextImporter(OverpassClient overpassClient, JdbcTemplate jdbc, TransactionTemplate transactions,
                              ApplicationEventPublisher events) {
        this.overpassClient = overpassClient;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.events = events;
    }

    /**
     * Downloads the city's institution areas and coastline, then recomputes its places' flags. A failed download is
     * logged and reported in the result; the city keeps its previous polygons and the other part still runs.
     *
     * @param pauseBetweenCalls the polite delay between two Overpass calls
     */
    public ContextImportResult importCity(OsmCity city, Runnable pauseBetweenCalls) {
        List<String> errors = new ArrayList<>();
        Integer institutions = null;
        Integer coastlines = null;
        try {
            log.info("Context import ({}): downloading institution areas from Overpass...", city.name());
            institutions = writeInstitutions(city.id(), overpassClient.fetchInstitutions(city.relationId()));
            jdbc.update("UPDATE cities SET institutions_imported_at = now() WHERE id = ?", city.id());
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            log.warn("Context import ({}): institution areas failed, keeping the previous ones: {}", city.name(),
                    e.getMessage());
            errors.add("institutions: " + e.getMessage());
        }

        pauseBetweenCalls.run();
        try {
            Map<String, Object> box = jdbc.queryForMap("SELECT south, west, north, east FROM cities WHERE id = ?",
                    city.id());
            if (box.get("south") == null) {
                throw new IllegalStateException("the city has no bounding box yet");
            }
            log.info("Context import ({}): downloading the coastline from Overpass...", city.name());
            coastlines = writeCoastlines(city.id(), overpassClient.fetchCoastline(
                    ((Number) box.get("south")).doubleValue() - COAST_PADDING,
                    ((Number) box.get("west")).doubleValue() - COAST_PADDING,
                    ((Number) box.get("north")).doubleValue() + COAST_PADDING,
                    ((Number) box.get("east")).doubleValue() + COAST_PADDING));
            jdbc.update("UPDATE cities SET coastline_imported_at = now() WHERE id = ?", city.id());
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            log.warn("Context import ({}): coastline failed, keeping the previous one: {}", city.name(), e.getMessage());
            errors.add("coastline: " + e.getMessage());
        }

        Flags flags = recomputeFlags(city.id());
        ContextImportResult result = new ContextImportResult(city.slug(), institutions, coastlines,
                flags.insideInstitution(), flags.nearSea(), errors);
        log.info("Context import ({}): {}", city.name(), result);
        return result;
    }

    /**
     * Admin: download and write one city's context now (409 when another context run is going on; 503 when both
     * downloads failed).
     */
    public ContextImportResult importNow(OsmCity city) {
        Runnable pauseBetweenCalls = () -> {
            try {
                Thread.sleep(ADMIN_CALL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(HttpStatus.CONFLICT, "A context import is already running");
        }
        try {
            ContextImportResult result = importCity(city, pauseBetweenCalls);
            if (result.institutionAreas() == null && result.coastlines() == null) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "OpenStreetMap (Overpass) is not available right now: " + String.join("; ", result.errors()));
            }
            events.publishEvent(new PlacesChangedEvent("context"));
            return result;
        } finally {
            running.set(false);
        }
    }

    /**
     * Sets inside_institution and near_sea of the city's places from the polygons / lines already stored
     * (after an import, or by admins after the realism rules changed).
     */
    public Flags recomputeFlags(long cityId) {
        transactions.executeWithoutResult(status -> {
            jdbc.update(FLAG_INSIDE_INSTITUTION, cityId);
            jdbc.update(FLAG_NEAR_SEA, NEAR_SEA_METERS, cityId);
        });
        Flags flags = new Flags(
                count("SELECT count(*) FROM places WHERE city_id = ? AND inside_institution", cityId),
                count("SELECT count(*) FROM places WHERE city_id = ? AND near_sea", cityId));
        log.info("Context flags of city {}: {}", cityId, flags);
        return flags;
    }

    public Flags recomputeAndNotify(long cityId) {
        Flags flags = recomputeFlags(cityId);
        events.publishEvent(new PlacesChangedEvent("context flags"));
        return flags;
    }

    int writeInstitutions(long cityId, List<OverpassResponse.Element> elements) {
        Map<String, InstitutionInput> inputs = new LinkedHashMap<>();
        for (OverpassResponse.Element element : elements) {
            InstitutionInput input = toInstitution(element);
            if (input != null) {
                inputs.putIfAbsent(input.osmId(), input);
            }
        }
        List<InstitutionInput> rows = new ArrayList<>(inputs.values());
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM institution_areas WHERE city_id = ?", cityId);
            jdbc.batchUpdate(UPSERT_INSTITUTION, rows, 500, (ps, row) -> {
                ps.setArray(1, ps.getConnection().createArrayOf("text", row.wayWkts().toArray()));
                ps.setLong(2, cityId);
                ps.setString(3, row.osmId());
                ps.setString(4, row.kind());
                ps.setString(5, row.name());
            });
        });
        int written = count("SELECT count(*) FROM institution_areas WHERE city_id = ?", cityId);
        log.info("Context import: {} institution areas written ({} elements, {} usable)", written, elements.size(),
                rows.size());
        return written;
    }

    int writeCoastlines(long cityId, List<OverpassResponse.Element> elements) {
        List<String[]> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OverpassResponse.Element element : elements) {
            String wkt = "way".equals(element.type()) ? lineWkt(element.geometry()) : null;
            String osmId = element.type() + "/" + element.id();
            if (wkt != null && seen.add(osmId)) {
                rows.add(new String[]{osmId, wkt});
            }
        }
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM coastlines WHERE city_id = ?", cityId);
            jdbc.batchUpdate(UPSERT_COASTLINE, rows, 500, (ps, row) -> {
                ps.setLong(1, cityId);
                ps.setString(2, row[0]);
                ps.setString(3, row[1]);
            });
        });
        int written = count("SELECT count(*) FROM coastlines WHERE city_id = ?", cityId);
        log.info("Context import: {} coastline ways written", written);
        return written;
    }

    // ---------- pure mapping (unit tested) ----------

    /**
     * A closed way or a multipolygon relation of an institution; null when it is not one or has no usable geometry.
     */
    static InstitutionInput toInstitution(OverpassResponse.Element element) {
        String kind = kind(element);
        if (kind == null || element.type() == null) {
            return null;
        }
        List<String> wkts = new ArrayList<>();
        if ("way".equals(element.type())) {
            List<OverpassResponse.Center> points = usable(element.geometry());
            // Only a closed ring is an area (an unclosed "school" way is a mapping error or a fence)
            if (points.size() < 4 || !samePoint(points.getFirst(), points.getLast())) {
                return null;
            }
            wkts.add(lineWkt(points));
        } else if ("relation".equals(element.type()) && element.members() != null) {
            for (OverpassResponse.Member member : element.members()) {
                String role = member.role() == null ? "" : member.role();
                if ("way".equals(member.type()) && (role.isEmpty() || role.equals("outer") || role.equals("inner"))) {
                    String wkt = lineWkt(member.geometry());
                    if (wkt != null) {
                        wkts.add(wkt);
                    }
                }
            }
        }
        if (wkts.isEmpty()) {
            return null;
        }
        String name = OsmAreaImporter.trimToNull(element.tag("name"));
        if (name != null && name.length() > 255) {
            name = name.substring(0, 255);
        }
        return new InstitutionInput(element.type() + "/" + element.id(), kind, name, wkts);
    }

    // "university", "hospital", "landuse:industrial", "military:barracks"; null = not an institution
    static String kind(OverpassResponse.Element element) {
        String amenity = element.tag("amenity");
        if (amenity != null && INSTITUTION_AMENITIES.contains(amenity)) {
            return amenity;
        }
        String landuse = element.tag("landuse");
        if (landuse != null && INSTITUTION_LANDUSES.contains(landuse)) {
            return "landuse:" + landuse;
        }
        String military = OsmAreaImporter.trimToNull(element.tag("military"));
        if (military != null && !"no".equals(military)) {
            return "military:" + (military.length() > 30 ? military.substring(0, 30) : military);
        }
        return null;
    }

    static String lineWkt(List<OverpassResponse.Center> geometry) {
        List<OverpassResponse.Center> points = usable(geometry);
        if (points.size() < 2) {
            return null;
        }
        StringBuilder wkt = new StringBuilder("LINESTRING(");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                wkt.append(',');
            }
            wkt.append(String.format(Locale.ROOT, "%.7f %.7f", points.get(i).lon(), points.get(i).lat()));
        }
        return wkt.append(')').toString();
    }

    private static List<OverpassResponse.Center> usable(List<OverpassResponse.Center> geometry) {
        return geometry == null ? List.of()
                : geometry.stream().filter(p -> p != null && p.lat() != null && p.lon() != null).toList();
    }

    private static boolean samePoint(OverpassResponse.Center a, OverpassResponse.Center b) {
        return Math.abs(a.lat() - b.lat()) < 1e-9 && Math.abs(a.lon() - b.lon()) < 1e-9;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    record InstitutionInput(String osmId, String kind, String name, List<String> wayWkts) {
    }

    public record Flags(int insideInstitution, int nearSea) {
    }

    /**
     * @param institutionAreas the city's institution polygons after the run; null = download failed (previous kept)
     * @param coastlines       the city's coastline ways after the run; null = download failed (previous kept)
     * @param errors           what failed; empty = fine
     */
    public record ContextImportResult(String city, Integer institutionAreas, Integer coastlines,
                                      int placesInsideInstitution, int placesNearSea, List<String> errors) {
    }
}

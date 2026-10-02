package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.osm.OverpassResponse.Center;
import com.nomi.wayfinder.osm.OverpassResponse.Element;
import com.nomi.wayfinder.osm.OverpassResponse.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * OpenStreetMap from a Geofabrik extract of Türkiye (.osm.pbf) instead of the Overpass API. The extract is prepared
 * once into a DuckDB file (PREPARE, ~1-2 min for all of Türkiye): the elements the Overpass queries of OverpassClient
 * select, the geometry of the ways they need and the relations' members. Every fetch then answers from that file in
 * seconds, in the same shape as an Overpass answer, so the importers work unchanged.
 * "In the city" (Overpass: area(id)) is the element's point / centre inside the city polygon already imported from the
 * provinces (cities.geom); Adana: 2165 places here vs 2166 from Overpass.
 */
public class PbfOsmSource implements OsmSource {

    private static final Logger log = LoggerFactory.getLogger(PbfOsmSource.class);
    // Bump when the cache tables change: an older cache is rebuilt
    static final String CACHE_VERSION = "1";
    private static final TypeReference<Map<String, String>> TAGS = new TypeReference<>() {
    };
    private static final TypeReference<List<Member>> MEMBERS = new TypeReference<>() {
    };
    private static final TypeReference<List<Center>> GEOMETRY = new TypeReference<>() {
    };

    // The same filters as OverpassClient's queries (FOOD_QUERY, SIGHTS_QUERY, AREAS_QUERY, DISTRICTS_QUERY,
    // PROVINCES_QUERY, INSTITUTIONS_QUERY, COASTLINE_QUERY); %s = the extract's path
    static final String PREPARE = """
            CREATE OR REPLACE TABLE sel AS
            SELECT kind::VARCHAR AS kind, id, tags, refs, ref_roles, ref_types::VARCHAR[] AS ref_types, lat, lon,
              CASE
                WHEN kind = 'node' AND tags['place'] IN ('suburb', 'quarter', 'neighbourhood')
                     AND tags['name'] IS NOT NULL THEN 'area'
                WHEN kind = 'way' AND tags['natural'] = 'coastline' THEN 'coastline'
                WHEN kind = 'relation' AND tags['boundary'] = 'administrative' AND tags['admin_level'] IN ('4', '6')
                     THEN 'boundary'
                WHEN tags['name'] IS NOT NULL AND (
                     tags['amenity'] IN ('cafe', 'restaurant', 'fast_food', 'food_court', 'ice_cream', 'theatre',
                                         'arts_centre', 'place_of_worship', 'marketplace')
                  OR tags['shop'] IN ('pastry', 'confectionery', 'bakery', 'coffee', 'supermarket', 'convenience',
                                      'grocery')
                  OR tags['tourism'] IN ('museum', 'gallery', 'attraction', 'viewpoint', 'zoo', 'aquarium', 'theme_park')
                  OR tags['leisure'] IN ('park', 'garden', 'nature_reserve')
                  OR tags['historic'] IS NOT NULL
                  OR tags['natural'] = 'beach'
                  OR tags['man_made'] = 'lighthouse'
                  OR (tags['tourism'] = 'artwork' AND regexp_matches(coalesce(tags['artwork_type'], ''), 'mural|graffiti'))
                  -- Synagogues and cemevleri mapped only by religion / denomination or by name (OverpassClient's
                  -- MARKETS_WORSHIP_QUERY); OsmPlaceMapper drops streets, cemeteries and namesakes
                  OR tags['religion'] = 'jewish'
                  OR regexp_matches(coalesce(tags['denomination'], ''), '^(alevi|bektashi)')
                  OR regexp_matches(tags['name'], '[Cc]em ?[Ee]v|CEM ?EV|[Ss]inagog|SİNAGOG|[Hh]avra|HAVRA')
                ) THEN 'place'
                WHEN kind IN ('way', 'relation') AND (
                     tags['amenity'] IN ('university', 'college', 'school', 'hospital', 'prison')
                  OR tags['landuse'] IN ('military', 'industrial')
                  OR tags['military'] IS NOT NULL
                ) THEN 'institution'
              END AS purpose
            FROM ST_ReadOSM('%1$s')
            WHERE kind IN ('node', 'way', 'relation') AND tags IS NOT NULL AND cardinality(tags) > 0;
            DELETE FROM sel WHERE purpose IS NULL;

            CREATE OR REPLACE TABLE rel_members AS
            SELECT s.id AS rel_id, m.pos, m.ref, m.role, m.type
            FROM sel s, LATERAL (SELECT generate_subscripts(s.refs, 1) AS pos, unnest(s.refs) AS ref,
                                        unnest(s.ref_roles) AS role, unnest(s.ref_types) AS type) m
            WHERE s.kind = 'relation';

            CREATE OR REPLACE TEMP TABLE need_ways AS
            SELECT id FROM sel WHERE kind = 'way'
            UNION SELECT ref FROM rel_members WHERE type = 'way';

            CREATE OR REPLACE TEMP TABLE ways AS
            SELECT w.id, w.refs FROM ST_ReadOSM('%1$s') w SEMI JOIN need_ways n ON w.id = n.id WHERE w.kind = 'way';

            CREATE OR REPLACE TEMP TABLE need_nodes AS
            SELECT DISTINCT unnest(refs) AS id FROM ways
            UNION SELECT ref FROM rel_members WHERE type = 'node';

            CREATE OR REPLACE TABLE node_xy AS
            SELECT n.id, n.lat, n.lon FROM ST_ReadOSM('%1$s') n SEMI JOIN need_nodes x ON n.id = x.id
            WHERE n.kind = 'node';

            -- Each way's points in order, and its bounding box
            CREATE OR REPLACE TABLE way_geom AS
            SELECT u.id, list(x.lat ORDER BY u.pos) AS lats, list(x.lon ORDER BY u.pos) AS lons,
                   min(x.lat) AS s, min(x.lon) AS w, max(x.lat) AS n, max(x.lon) AS e
            FROM (SELECT id, unnest(refs) AS nid, generate_subscripts(refs, 1) AS pos FROM ways) u
            JOIN node_xy x ON x.id = u.nid
            GROUP BY u.id;

            -- A relation's bounding box: of its member ways and nodes
            CREATE OR REPLACE TEMP TABLE rel_bbox AS
            SELECT m.rel_id AS id, min(coalesce(g.s, x.lat)) AS s, min(coalesce(g.w, x.lon)) AS w,
                   max(coalesce(g.n, x.lat)) AS n, max(coalesce(g.e, x.lon)) AS e
            FROM rel_members m
            LEFT JOIN way_geom g ON m.type = 'way' AND g.id = m.ref
            LEFT JOIN node_xy x ON m.type = 'node' AND x.id = m.ref
            GROUP BY m.rel_id;

            -- Every selected element with its point: a node's position, a way's / relation's bounding box centre
            -- (Overpass "out center")
            CREATE OR REPLACE TABLE elem AS
            SELECT s.kind, s.id, s.purpose, s.tags,
                   CASE s.kind WHEN 'node' THEN s.lat WHEN 'way' THEN (g.s + g.n) / 2 ELSE (r.s + r.n) / 2 END AS lat,
                   CASE s.kind WHEN 'node' THEN s.lon WHEN 'way' THEN (g.w + g.e) / 2 ELSE (r.w + r.e) / 2 END AS lon
            FROM sel s
            LEFT JOIN way_geom g ON s.kind = 'way' AND g.id = s.id
            LEFT JOIN rel_bbox r ON s.kind = 'relation' AND r.id = s.id;

            CREATE OR REPLACE TABLE meta (k VARCHAR, v VARCHAR);
            """;

    // Elements of a purpose whose point lies in the polygon (?1 = WKT); the box pre-filters cheaply
    private static final String IN_POLYGON = """
            WITH c AS (SELECT ST_GeomFromText(?::VARCHAR) AS g)
            SELECT e.kind, e.id, e.lat, e.lon, to_json(e.tags) AS tags
            FROM elem e, c
            WHERE e.purpose = ?::VARCHAR AND e.lat IS NOT NULL
              AND e.lon BETWEEN ST_XMin(c.g) AND ST_XMax(c.g) AND e.lat BETWEEN ST_YMin(c.g) AND ST_YMax(c.g)
              AND ST_Contains(c.g, ST_Point(e.lon, e.lat))
            """;

    // A relation's members in order: ways with their points, nodes with their position (Overpass "out geom")
    private static final String MEMBERS_OF = """
            SELECT m.rel_id, to_json(list({'type': m.type, 'ref': m.ref, 'role': m.role, 'lat': x.lat, 'lon': x.lon,
                     'geometry': CASE WHEN m.type = 'way' AND g.id IS NOT NULL THEN
                         list_transform(generate_series(1, len(g.lats)), lambda i: {'lat': g.lats[i], 'lon': g.lons[i]})
                       END} ORDER BY m.pos)) AS members
            FROM rel_members m
            LEFT JOIN way_geom g ON m.type = 'way' AND g.id = m.ref
            LEFT JOIN node_xy x ON m.type = 'node' AND x.id = m.ref
            WHERE m.rel_id IN (SELECT unnest(?::BIGINT[]))
            GROUP BY m.rel_id
            """;

    private static final String GEOMETRY_OF = """
            SELECT id, to_json(list_transform(generate_series(1, len(lats)), lambda i: {'lat': lats[i], 'lon': lons[i]}))
                   AS geometry
            FROM way_geom WHERE id IN (SELECT unnest(?::BIGINT[]))
            """;

    private final OsmPbfProperties properties;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private volatile boolean ready;

    public PbfOsmSource(OsmPbfProperties properties, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<Element> fetchProvinces() {
        return query(conn -> {
            List<Element> relations = select(conn, """
                    SELECT kind, id, lat, lon, to_json(tags) AS tags FROM elem
                    WHERE purpose = 'boundary' AND tags['admin_level'] = '4' AND tags['ISO3166-2'] LIKE 'TR-%'
                    """);
            return withMembers(conn, relations);
        });
    }

    @Override
    public List<Element> fetchPlaces(long relationId) {
        String city = cityPolygon(relationId);
        return query(conn -> inPolygon(conn, city, "place"));
    }

    @Override
    public List<Element> fetchDistricts(long relationId) {
        String city = cityPolygon(relationId);
        return query(conn -> withMembers(conn, inPolygon(conn, city, "boundary").stream()
                .filter(e -> "relation".equals(e.type()) && "6".equals(e.tag("admin_level")))
                .toList()));
    }

    @Override
    public List<Element> fetchAreas(long relationId) {
        String city = cityPolygon(relationId);
        return query(conn -> inPolygon(conn, city, "area"));
    }

    @Override
    public List<Element> fetchInstitutions(long relationId) {
        String city = cityPolygon(relationId);
        return query(conn -> {
            List<Element> found = inPolygon(conn, city, "institution");
            List<Element> ways = withGeometry(conn, found.stream().filter(e -> "way".equals(e.type())).toList());
            List<Element> relations = withMembers(conn, found.stream().filter(e -> "relation".equals(e.type())).toList());
            List<Element> all = new ArrayList<>(ways);
            all.addAll(relations);
            return all;
        });
    }

    @Override
    public List<Element> fetchCoastline(double south, double west, double north, double east) {
        return query(conn -> {
            List<Element> ways = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT s.id, to_json(s.tags) AS tags,
                           to_json(list_transform(generate_series(1, len(g.lats)),
                                                  lambda i: {'lat': g.lats[i], 'lon': g.lons[i]})) AS geometry
                    FROM sel s JOIN way_geom g ON g.id = s.id
                    WHERE s.purpose = 'coastline' AND g.n >= ? AND g.s <= ? AND g.e >= ? AND g.w <= ?
                    """)) {
                ps.setDouble(1, south);
                ps.setDouble(2, north);
                ps.setDouble(3, west);
                ps.setDouble(4, east);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ways.add(new Element("way", rs.getLong("id"), null, null, null, tags(rs.getString("tags")),
                                null, jsonMapper.readValue(rs.getString("geometry"), GEOMETRY)));
                    }
                }
            }
            return ways;
        });
    }

    // ---------- queries ----------

    private List<Element> inPolygon(Connection conn, String polygonWkt, String purpose) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(IN_POLYGON)) {
            ps.setString(1, polygonWkt);
            ps.setString(2, purpose);
            return read(ps);
        }
    }

    private List<Element> select(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            return read(ps);
        }
    }

    // Nodes keep their position; ways and relations get their centre (Overpass "out center")
    private List<Element> read(PreparedStatement ps) throws SQLException {
        List<Element> elements = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String kind = rs.getString("kind");
                double lat = rs.getDouble("lat");
                double lon = rs.getDouble("lon");
                Map<String, String> tags = tags(rs.getString("tags"));
                elements.add("node".equals(kind)
                        ? new Element(kind, rs.getLong("id"), lat, lon, null, tags)
                        : new Element(kind, rs.getLong("id"), null, null, new Center(lat, lon), tags));
            }
        }
        return elements;
    }

    private List<Element> withMembers(Connection conn, List<Element> relations) throws SQLException {
        if (relations.isEmpty()) {
            return relations;
        }
        Map<Long, List<Member>> members = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(MEMBERS_OF)) {
            ps.setArray(1, conn.createArrayOf("BIGINT", relations.stream().map(Element::id).toArray(Long[]::new)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.put(rs.getLong("rel_id"), jsonMapper.readValue(rs.getString("members"), MEMBERS));
                }
            }
        }
        return relations.stream()
                .map(r -> new Element(r.type(), r.id(), null, null, r.center(), r.tags(),
                        members.getOrDefault(r.id(), List.of()), null))
                .toList();
    }

    private List<Element> withGeometry(Connection conn, List<Element> ways) throws SQLException {
        if (ways.isEmpty()) {
            return ways;
        }
        Map<Long, List<Center>> geometry = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(GEOMETRY_OF)) {
            ps.setArray(1, conn.createArrayOf("BIGINT", ways.stream().map(Element::id).toArray(Long[]::new)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    geometry.put(rs.getLong("id"), jsonMapper.readValue(rs.getString("geometry"), GEOMETRY));
                }
            }
        }
        return ways.stream()
                .map(w -> new Element(w.type(), w.id(), null, null, w.center(), w.tags(), null,
                        geometry.getOrDefault(w.id(), List.of())))
                .toList();
    }

    private Map<String, String> tags(String json) {
        return json == null || json.isBlank() ? Map.of() : jsonMapper.readValue(json, TAGS);
    }

    // The city polygon from the provinces import (cities.geom); relation id as stored in cities.osm_id
    private String cityPolygon(long relationId) {
        List<String> wkt = jdbc.queryForList("SELECT ST_AsText(geom) FROM cities WHERE osm_id = ? AND geom IS NOT NULL",
                String.class, "relation/" + relationId);
        if (wkt.isEmpty()) {
            throw new IllegalStateException("No polygon for city relation " + relationId + " (import the provinces)");
        }
        return wkt.getFirst();
    }

    // ---------- the DuckDB cache ----------

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection conn) throws Exception;
    }

    private <T> T query(Work<T> work) {
        ensureCache();
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + cacheFile().getPath(), readOnly)) {
            setup(conn);
            return work.run(conn);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Reading the OSM extract failed: " + e.getMessage(), e);
        }
    }

    // Builds the cache when it is missing, older than the extract or of another CACHE_VERSION
    synchronized void ensureCache() {
        if (ready) {
            return;
        }
        File pbf = new File(properties.path());
        if (!pbf.isFile()) {
            throw new IllegalStateException("OSM extract not found: " + pbf + " (nomi.osm-pbf.path)");
        }
        File cache = cacheFile();
        String stamp = CACHE_VERSION + ":" + pbf.getName() + ":" + pbf.length() + ":" + pbf.lastModified();
        if (cache.isFile() && stamp.equals(readStamp(cache))) {
            ready = true;
            return;
        }
        long started = System.currentTimeMillis();
        log.info("OSM extract: preparing {} into {} (a minute or two)...", pbf, cache);
        cache.delete();
        new File(cache.getPath() + ".wal").delete();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + cache.getPath());
             Statement st = conn.createStatement()) {
            setup(conn);
            String path = pbf.getAbsolutePath().replace('\\', '/').replace("'", "''");
            for (String sql : PREPARE.formatted(path).split(";\\s*\\n")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
            st.execute("INSERT INTO meta VALUES ('stamp', '" + stamp.replace("'", "''") + "')");
            st.execute("CHECKPOINT");
        } catch (SQLException e) {
            throw new IllegalStateException("Preparing the OSM extract failed: " + e.getMessage(), e);
        }
        log.info("OSM extract ready in {} s", (System.currentTimeMillis() - started) / 1000);
        ready = true;
    }

    private String readStamp(File cache) {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + cache.getPath(), readOnly);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT v FROM meta WHERE k = 'stamp'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private void setup(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("INSTALL spatial");
            st.execute("LOAD spatial");
            String limit = properties.memoryLimit() == null ? "2GB"
                    : properties.memoryLimit().replaceAll("[^0-9A-Za-z]", "");
            st.execute("SET memory_limit = '" + limit + "'");
        }
    }

    private File cacheFile() {
        String path = properties.cachePath();
        if (path == null || path.isBlank()) {
            path = properties.path().replaceAll("\\.osm\\.pbf$", "") + "-cache.duckdb";
        }
        return new File(path);
    }
}

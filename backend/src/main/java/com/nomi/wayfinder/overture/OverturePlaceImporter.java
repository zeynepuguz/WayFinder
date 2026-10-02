package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.osm.OsmAreaImporter;
import com.nomi.wayfinder.osm.OsmContextImporter;
import com.nomi.wayfinder.osm.OsmPlaceImporter;
import com.nomi.wayfinder.osm.PlacesChangedEvent;
import com.nomi.wayfinder.overture.OvertureMapper.OverturePlace;
import com.nomi.wayfinder.overture.OvertureMatcher.ExistingPlace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Imports one city's food places from Overture Maps next to its OpenStreetMap places (overture/OvertureMatcher):
 * - an OSM / verified food place Overture also knows is confirmed: it gets the Overture id and, when it has none,
 *   the phone / website; unconfirmed = false
 * - an OSM food place (without Wikidata) no Overture source knows is unconfirmed (likely closed): left out of lists,
 *   plans and suggestions. Skipped when the download looks incomplete (far fewer Overture than OSM places)
 * - Overture places OSM lacks (confidence >= minConfidence) are added with source OVERTURE; OVERTURE rows of earlier
 *   releases that are gone (closed) or now match an OSM place are hidden (route stops / saved places keep them)
 * Everything happens inside the city's bounding box (neighbour cities' edges included: the whole box was read).
 * Then city, district and context flags (campus, near the sea) are assigned as for OSM places.
 */
@Service
public class OverturePlaceImporter {

    private static final Logger log = LoggerFactory.getLogger(OverturePlaceImporter.class);
    static final int CHUNK_SIZE = 1000;
    // Fewer Overture than this share of the box's OSM food places: the download is incomplete, nothing is unconfirmed
    static final double MIN_COVERAGE = 0.2;
    static final int COVERAGE_CHECK_ABOVE = 50;

    private static final String FOOD = "('BREAKFAST', 'RESTAURANT', 'CAFE', 'DESSERT')";
    // What Overture places may confirm: food, markets, places of worship and sights (a famous mosque is an ATTRACTION)
    private static final String MATCHABLE = "('BREAKFAST', 'RESTAURANT', 'CAFE', 'DESSERT', 'MARKET', 'WORSHIP', 'ATTRACTION')";
    // What may be unconfirmed: food places and markets (Overture knows too few of the mosques to judge those)
    private static final String CONFIRMABLE = "('BREAKFAST', 'RESTAURANT', 'CAFE', 'DESSERT', 'MARKET')";
    private static final Set<String> FOOD_CATEGORIES = Set.of("BREAKFAST", "RESTAURANT", "CAFE", "DESSERT");
    // The box exactly as Overture was read (planar lon / lat). A geography && alone is not enough: it compares
    // geodesic boxes, which reach well outside the lon / lat range (it only pre-filters through the GiST index)
    static final String IN_BOX = "location && CAST(ST_MakeEnvelope(?, ?, ?, ?, 4326) AS geography)"
            + " AND ST_Intersects(CAST(location AS geometry), ST_MakeEnvelope(?, ?, ?, ?, 4326))";

    private static final String EXISTING = """
            SELECT id, name, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, source, category
            FROM places
            WHERE source <> 'OVERTURE' AND NOT hidden AND category IN %s AND %s
            """.formatted(MATCHABLE, IN_BOX);

    // Overture ids move to the rows that now hold them: freed first (the column is unique). The phone / website
    // came with the match (OSM rows have none of their own), so they go too
    private static final String RELEASE_IDS_OF_OTHER_ROWS = """
            UPDATE places SET overture_id = NULL, overture_confidence = NULL, phone = NULL, website = NULL,
                              updated_at = now()
            WHERE source <> 'OVERTURE' AND overture_id IS NOT NULL AND NOT (id = ANY (?)) AND %s
            """.formatted(IN_BOX);
    // An id this import confirms may still sit on a row outside the box: a place on the border a neighbouring
    // city's import matched (their Overture boxes overlap). That row lets go of it (the column is unique)
    private static final String RELEASE_CONFIRMING_IDS_ELSEWHERE = """
            UPDATE places SET overture_id = NULL, overture_confidence = NULL, phone = NULL, website = NULL,
                              updated_at = now()
            WHERE source <> 'OVERTURE' AND overture_id = ANY (?) AND NOT (id = ANY (?))
            """;
    // The rows this import confirms let go of their old ids first: ids can swap between them (A had Y and now gets X
    // while B gets Y), and the unique column would refuse B before A is updated
    private static final String CLEAR_IDS_OF_CONFIRMED_ROWS = """
            UPDATE places SET overture_id = NULL WHERE id = ANY (?) AND overture_id IS NOT NULL
            """;
    private static final String RETIRE_OVERTURE_ROWS_NOW_CONFIRMING = """
            UPDATE places SET overture_id = NULL, hidden = TRUE, updated_at = now()
            WHERE source = 'OVERTURE' AND overture_id = ANY (?)
            """;
    private static final String CONFIRM = """
            UPDATE places SET overture_id = ?, overture_confidence = ?, phone = coalesce(phone, ?),
                              website = coalesce(website, ?), unconfirmed = FALSE, updated_at = now()
            WHERE id = ?
            """;
    private static final String UPSERT = """
            INSERT INTO places (name, location, category, indoor, tags, source, overture_id, overture_confidence,
                                phone, website, created_at, updated_at)
            VALUES (?, CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography), ?, ?, ?, 'OVERTURE', ?, ?, ?, ?,
                    now(), now())
            ON CONFLICT (overture_id) DO UPDATE SET
                name = EXCLUDED.name, location = EXCLUDED.location, category = EXCLUDED.category,
                indoor = EXCLUDED.indoor, tags = EXCLUDED.tags, overture_confidence = EXCLUDED.overture_confidence,
                phone = EXCLUDED.phone, website = EXCLUDED.website, hidden = FALSE, unconfirmed = FALSE,
                updated_at = now()
            WHERE places.source = 'OVERTURE'
            """;
    private static final String HIDE_GONE_OVERTURE_ROWS = """
            UPDATE places SET hidden = TRUE, updated_at = now()
            WHERE source = 'OVERTURE' AND NOT hidden AND (overture_id IS NULL OR NOT (overture_id = ANY (?))) AND %s
            """.formatted(IN_BOX);
    // Unconfirmed (hidden as likely closed): an OSM place last edited years ago without phone / website / hours
    // (osm_stale) that no Overture source knows. Verified (hand-checked) and notable (Wikidata) places never are
    private static final String FLAG_UNCONFIRMED = """
            UPDATE places SET unconfirmed = (osm_stale AND NOT (id = ANY (?))), updated_at = now()
            WHERE source = 'OSM' AND wikidata IS NULL AND category IN %s AND %s
              AND unconfirmed IS DISTINCT FROM (osm_stale AND NOT (id = ANY (?)))
            """.formatted(CONFIRMABLE, IN_BOX);

    private final OvertureClient client;
    private final OvertureProperties properties;
    private final OsmAreaImporter areaImporter;
    private final OsmContextImporter contextImporter;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;

    public OverturePlaceImporter(OvertureClient client, OvertureProperties properties, OsmAreaImporter areaImporter,
                                 OsmContextImporter contextImporter, JdbcTemplate jdbc,
                                 TransactionTemplate transactions, ApplicationEventPublisher events) {
        this.client = client;
        this.properties = properties;
        this.areaImporter = areaImporter;
        this.contextImporter = contextImporter;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.events = events;
    }

    /**
     * A city with its bounding box (cities.south / west / north / east).
     */
    public record CityBox(long id, String slug, String name, double south, double west, double north, double east) {
    }

    public Optional<CityBox> findCity(String slug) {
        return jdbc.query("""
                        SELECT id, slug, name, south, west, north, east FROM cities
                        WHERE slug = ? AND south IS NOT NULL AND west IS NOT NULL
                        """,
                (rs, i) -> new CityBox(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                        rs.getDouble("south"), rs.getDouble("west"), rs.getDouble("north"), rs.getDouble("east")),
                slug).stream().findFirst();
    }

    public String latestRelease() {
        return client.latestRelease();
    }

    public ImportResult importCity(CityBox city, String release) {
        long started = System.currentTimeMillis();
        log.info("Overture import ({}): downloading food places of release {}...", city.name(), release);
        List<OvertureMapper.OvertureRow> rows = client.fetchFood(release, city.south(), city.west(), city.north(),
                city.east());
        List<OverturePlace> mapped = rows.stream().map(OvertureMapper::map).filter(Objects::nonNull).toList();

        Object[] box = {city.west(), city.south(), city.east(), city.north(),
                city.west(), city.south(), city.east(), city.north()};
        List<ExistingPlace> existing = jdbc.query(EXISTING,
                (rs, i) -> new ExistingPlace(rs.getLong("id"), rs.getString("name"), rs.getDouble("lat"),
                        rs.getDouble("lon"), rs.getString("source"),
                        com.nomi.wayfinder.entity.PlaceCategory.valueOf(rs.getString("category"))), box);
        OvertureMatcher.Result matched = OvertureMatcher.match(mapped, existing, properties.minConfidence());

        Long[] confirmedIds = matched.confirmed().keySet().toArray(Long[]::new);
        String[] confirmingOvertureIds = matched.confirmed().values().stream()
                .map(OverturePlace::overtureId).toArray(String[]::new);
        transactions.executeWithoutResult(status -> {
            jdbc.update(con -> {
                var ps = con.prepareStatement(RELEASE_IDS_OF_OTHER_ROWS);
                ps.setArray(1, con.createArrayOf("bigint", confirmedIds));
                bindBox(ps, 2, city);
                return ps;
            });
            jdbc.update(con -> {
                var ps = con.prepareStatement(CLEAR_IDS_OF_CONFIRMED_ROWS);
                ps.setArray(1, con.createArrayOf("bigint", confirmedIds));
                return ps;
            });
            jdbc.update(con -> {
                var ps = con.prepareStatement(RELEASE_CONFIRMING_IDS_ELSEWHERE);
                ps.setArray(1, con.createArrayOf("text", confirmingOvertureIds));
                ps.setArray(2, con.createArrayOf("bigint", confirmedIds));
                return ps;
            });
            jdbc.update(con -> {
                var ps = con.prepareStatement(RETIRE_OVERTURE_ROWS_NOW_CONFIRMING);
                ps.setArray(1, con.createArrayOf("text", confirmingOvertureIds));
                return ps;
            });
        });
        List<Map.Entry<Long, OverturePlace>> confirmations = new ArrayList<>(matched.confirmed().entrySet());
        for (int from = 0; from < confirmations.size(); from += CHUNK_SIZE) {
            List<Map.Entry<Long, OverturePlace>> chunk =
                    confirmations.subList(from, Math.min(confirmations.size(), from + CHUNK_SIZE));
            transactions.executeWithoutResult(status -> jdbc.batchUpdate(CONFIRM, chunk, CHUNK_SIZE, (ps, e) -> {
                OverturePlace o = e.getValue();
                ps.setString(1, o.overtureId());
                ps.setDouble(2, o.confidence());
                ps.setString(3, o.phone());
                ps.setString(4, o.website());
                ps.setLong(5, e.getKey());
            }));
        }

        List<OverturePlace> added = matched.added();
        for (int from = 0; from < added.size(); from += CHUNK_SIZE) {
            List<OverturePlace> chunk = added.subList(from, Math.min(added.size(), from + CHUNK_SIZE));
            transactions.executeWithoutResult(status -> jdbc.batchUpdate(UPSERT, chunk, CHUNK_SIZE, (ps, p) -> {
                ps.setString(1, p.name());
                ps.setDouble(2, p.longitude());
                ps.setDouble(3, p.latitude());
                ps.setString(4, p.category().name());
                ps.setBoolean(5, p.indoor());
                ps.setArray(6, ps.getConnection().createArrayOf("text", p.tags().toArray()));
                ps.setString(7, p.overtureId());
                ps.setDouble(8, p.confidence());
                ps.setString(9, p.phone());
                ps.setString(10, p.website());
            }));
        }

        String[] addedIds = added.stream().map(OverturePlace::overtureId).toArray(String[]::new);
        Integer hidden = transactions.execute(status -> jdbc.update(con -> {
            var ps = con.prepareStatement(HIDE_GONE_OVERTURE_ROWS);
            ps.setArray(1, con.createArrayOf("text", addedIds));
            bindBox(ps, 2, city);
            return ps;
        }));

        long osmFood = existing.stream()
                .filter(p -> "OSM".equals(p.source()) && FOOD_CATEGORIES.contains(p.category().name())).count();
        long overtureFood = mapped.stream().filter(p -> FOOD_CATEGORIES.contains(p.category().name())).count();
        boolean complete = osmFood <= COVERAGE_CHECK_ABOVE || overtureFood >= osmFood * MIN_COVERAGE;
        if (complete) {
            transactions.executeWithoutResult(status -> jdbc.update(con -> {
                var ps = con.prepareStatement(FLAG_UNCONFIRMED);
                ps.setArray(1, con.createArrayOf("bigint", confirmedIds));
                bindBox(ps, 2, city);
                ps.setArray(10, con.createArrayOf("bigint", confirmedIds));
                return ps;
            }));
        } else {
            log.warn("Overture import ({}): only {} Overture food places for {} OSM ones; the download looks "
                    + "incomplete, OSM places are not marked unconfirmed", city.name(), mapped.size(), osmFood);
        }
        int unconfirmed = count("SELECT count(*) FROM places WHERE unconfirmed AND " + IN_BOX, box);

        // New rows get their city, district and campus / sea flags like OSM places
        jdbc.update(OsmPlaceImporter.ASSIGN_CITY, city.id());
        areaImporter.assignPlaceDistricts(city.id());
        contextImporter.recomputeFlags(city.id());
        jdbc.update("UPDATE cities SET overture_imported_at = now(), overture_release = ? WHERE id = ?",
                release, city.id());
        events.publishEvent(new PlacesChangedEvent("overture"));

        ImportResult result = new ImportResult(city.slug(), release, rows.size(), mapped.size(),
                matched.confirmed().size(), added.size(), hidden == null ? 0 : hidden, unconfirmed, complete,
                (System.currentTimeMillis() - started) / 1000);
        log.info("Overture import ({}) finished: {}", city.name(), result);
        return result;
    }

    // Binds IN_BOX (west, south, east, north twice) from this parameter index on
    private static void bindBox(java.sql.PreparedStatement ps, int index, CityBox city) throws java.sql.SQLException {
        for (int i = 0; i < 8; i += 4) {
            ps.setDouble(index + i, city.west());
            ps.setDouble(index + i + 1, city.south());
            ps.setDouble(index + i + 2, city.east());
            ps.setDouble(index + i + 3, city.north());
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /**
     * @param downloaded  Overture food rows in the city's box
     * @param usable      of them, places we would show (named, not closed, not a canteen / bar, ...)
     * @param confirmed   OSM / verified places Overture confirmed
     * @param added       new places from Overture (inserted or refreshed)
     * @param hidden      Overture places of earlier releases hidden now (gone, or now an OSM place)
     * @param unconfirmed places in the box marked as unconfirmed after the run
     * @param complete    false = the download looked incomplete and unconfirmed flags were left as they were
     */
    public record ImportResult(String city, String release, int downloaded, int usable, int confirmed, int added,
                               int hidden, int unconfirmed, boolean complete, long seconds) {
    }
}

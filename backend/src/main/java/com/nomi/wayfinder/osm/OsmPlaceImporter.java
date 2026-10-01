package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.dto.OpeningHoursDto;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.osm.OsmPlaceMapper.OsmPlace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.Time;
import java.util.*;

/**
 * Imports / refreshes one city's places from OpenStreetMap (OsmCityImporter runs it for every city).
 *
 * Rows are upserted by osm_id with source = 'OSM'. Price, rating, description and visit length
 * stay NULL (OSM does not have them; we never invent them). Rows that disappeared from OSM are
 * NOT deleted: route stops and saved places may reference them. An OSM element that duplicates a
 * verified (non-OSM) place is skipped; the verified place only receives the element's wikidata /
 * Commons file when it has neither (for its photo), nothing else. An OSM row an admin
 * has since edited (source no longer 'OSM') is left alone as well.
 * wikidata / commons_file are refreshed on every run, so rows imported before they existed get them too.
 * Several OSM elements of one place (same category + name, close together) are merged into one; rows an
 * earlier import wrote for the dropped ones are deleted unless a route stop or saved place uses them.
 * After writing, the city's places get their city_id (city polygon; an element on the coast just outside it
 * still belongs to the city whose area query returned it) and district_id (district polygons).
 *
 * Plain JDBC batches in chunks of CHUNK_SIZE, each chunk in its own transaction, so ~12k rows take
 * seconds and a failure only loses the current chunk.
 */
@Service
public class OsmPlaceImporter {

    private static final Logger log = LoggerFactory.getLogger(OsmPlaceImporter.class);
    static final int CHUNK_SIZE = 500;
    // Names of filtered elements kept for the import result / log
    static final int MAX_EXAMPLES = 20;

    private static final String UPSERT = """
            INSERT INTO places (name, name_en, cuisine, location, category, indoor, tags, source, source_url, osm_id,
                                address, neighborhood, wikidata, commons_file, osm_stale, created_at, updated_at)
            VALUES (?, ?, ?, CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography), ?, ?, ?, 'OSM', ?, ?, ?, ?, ?, ?, ?, now(), now())
            ON CONFLICT (osm_id) DO UPDATE SET
                -- A new wikidata id / Commons file means the photo must be looked up again
                image_checked_at = CASE
                    WHEN places.wikidata IS DISTINCT FROM EXCLUDED.wikidata
                      OR places.commons_file IS DISTINCT FROM EXCLUDED.commons_file THEN NULL
                    ELSE places.image_checked_at END,
                wikidata = EXCLUDED.wikidata,
                commons_file = EXCLUDED.commons_file,
                name = EXCLUDED.name,
                name_en = EXCLUDED.name_en,
                cuisine = EXCLUDED.cuisine,
                location = EXCLUDED.location,
                category = EXCLUDED.category,
                indoor = EXCLUDED.indoor,
                tags = EXCLUDED.tags,
                source_url = EXCLUDED.source_url,
                address = EXCLUDED.address,
                neighborhood = EXCLUDED.neighborhood,
                osm_stale = EXCLUDED.osm_stale,
                -- It passed the realism filter this time (PlaceRealismFilter); an element whose Wikidata item is an event
                -- stays hidden while it keeps that item (PlacePopularityService)
                hidden = (places.not_a_place AND places.wikidata IS NOT DISTINCT FROM EXCLUDED.wikidata),
                not_a_place = (places.not_a_place AND places.wikidata IS NOT DISTINCT FROM EXCLUDED.wikidata),
                updated_at = now()
            WHERE places.source = 'OSM'
            """;

    // Only the two photo references, only onto a verified place that has neither yet
    private static final String COPY_MEDIA_TO_VERIFIED = """
            UPDATE places SET wikidata = ?, commons_file = ?
            WHERE id = ? AND source NOT IN ('OSM', 'OVERTURE') AND wikidata IS NULL AND commons_file IS NULL
            """;

    // Rows of earlier imports; kept when a route, a saved place or a user photo points at them
    // (user_photos would be deleted with the place: ON DELETE CASCADE)
    static final String DELETE_UNREFERENCED = """
            DELETE FROM places p
            WHERE p.source = 'OSM' AND p.osm_id = ANY (?)
              AND NOT EXISTS (SELECT 1 FROM route_stops rs WHERE rs.place_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM saved_places sp WHERE sp.place_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM user_photos up WHERE up.place_id = p.id)
            """;

    // Rows of earlier imports that are no longer realistic places but are still referenced: hidden, not deleted
    private static final String HIDE = """
            UPDATE places SET hidden = TRUE, updated_at = now()
            WHERE source = 'OSM' AND osm_id = ANY (?) AND NOT hidden
            """;

    // Places inside the city polygon; the padded bounding box lets the geography GiST index pre-filter
    // Also used by overture/OverturePlaceImporter for the places it adds
    public static final String ASSIGN_CITY = """
            UPDATE places p SET city_id = c.id
            FROM cities c
            WHERE c.id = ? AND c.geom IS NOT NULL
              AND p.location && CAST(ST_MakeEnvelope(c.west - 0.05, c.south - 0.05, c.east + 0.05, c.north + 0.05, 4326)
                                     AS geography)
              AND ST_Intersects(c.geom, p.location::geometry)
              AND p.city_id IS DISTINCT FROM c.id
            """;

    // Elements this city's area query returned that lie outside every city polygon (piers, coastline)
    private static final String ASSIGN_CITY_OUTSIDE_POLYGONS = """
            UPDATE places SET city_id = ? WHERE city_id IS NULL AND osm_id = ANY (?)
            """;

    private final OverpassClient overpassClient;
    private final OsmAreaImporter areaImporter;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public OsmPlaceImporter(OverpassClient overpassClient, OsmAreaImporter areaImporter, JdbcTemplate jdbc,
                            TransactionTemplate transactions) {
        this.overpassClient = overpassClient;
        this.areaImporter = areaImporter;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public boolean hasOsmPlaces() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM places WHERE source = 'OSM')", Boolean.class));
    }

    // Downloads and writes the city's places, then assigns city / district to them (the caller serializes runs)
    public ImportResult importCity(OsmCity city) {
        long started = System.currentTimeMillis();
        log.info("OSM import ({}): downloading places from Overpass...", city.name());
        List<OverpassResponse.Element> elements = overpassClient.fetchPlaces(city.relationId());
        log.info("OSM import ({}): {} elements downloaded, writing to the database...", city.name(), elements.size());

        ImportResult result = importElements(elements, city);
        log.info("OSM import ({}) places finished in {} s: {}", city.name(),
                (System.currentTimeMillis() - started) / 1000, result);
        return result;
    }

    ImportResult importElements(List<OverpassResponse.Element> elements, OsmCity city) {
        OsmDeduplicator deduplicator = new OsmDeduplicator(jdbc.query("""
                        SELECT id, name, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon,
                               (wikidata IS NOT NULL OR commons_file IS NOT NULL) AS has_media
                        FROM places WHERE source NOT IN ('OSM', 'OVERTURE')
                        """,
                (rs, i) -> new OsmDeduplicator.ExistingPlace(rs.getLong("id"), rs.getString("name"),
                        rs.getDouble("lat"), rs.getDouble("lon"), rs.getBoolean("has_media"))));

        Prepared prepared = prepare(elements, deduplicator);

        int inserted = 0;
        int updated = 0;
        int skippedEdited = 0;
        List<OsmPlace> places = prepared.places();
        for (int from = 0; from < places.size(); from += CHUNK_SIZE) {
            List<OsmPlace> chunk = places.subList(from, Math.min(places.size(), from + CHUNK_SIZE));
            ChunkResult chunkResult = transactions.execute(status -> writeChunk(chunk));
            inserted += chunkResult.inserted();
            updated += chunkResult.updated();
            skippedEdited += chunkResult.skippedEdited();

            int done = Math.min(places.size(), from + CHUNK_SIZE);
            if (done % 2000 < CHUNK_SIZE || done == places.size()) {
                log.info("OSM import: {}/{} places written", done, places.size());
            }
        }

        List<VerifiedMedia> media = prepared.verifiedMedia();
        int copied = 0;
        if (!media.isEmpty()) {
            int[][] counts = transactions.execute(status ->
                    jdbc.batchUpdate(COPY_MEDIA_TO_VERIFIED, media, CHUNK_SIZE, (ps, m) -> {
                        ps.setString(1, m.wikidata());
                        ps.setString(2, m.commonsFile());
                        ps.setLong(3, m.placeId());
                    }));
            // Drivers may report SUCCESS_NO_INFO (-2) instead of a row count
            copied = counts == null ? 0 : Arrays.stream(counts).flatMapToInt(Arrays::stream).map(n -> Math.max(n, 0)).sum();
            log.info("OSM import: photo references copied to {} verified places", copied);
        }

        int removed = 0;
        if (!prepared.osmDuplicateIds().isEmpty()) {
            String[] ids = prepared.osmDuplicateIds().toArray(String[]::new);
            Integer deleted = transactions.execute(status -> jdbc.update(con -> {
                var ps = con.prepareStatement(DELETE_UNREFERENCED);
                ps.setArray(1, con.createArrayOf("text", ids));
                return ps;
            }));
            removed = deleted == null ? 0 : deleted;
            log.info("OSM import: {} elements were another copy of a place; {} rows of earlier imports removed",
                    ids.length, removed);
        }

        RemovedRows unrealistic = removeOrHide(prepared.unrealisticIds());
        if (!prepared.unrealisticIds().isEmpty()) {
            log.info("OSM import ({}): {} elements are not realistic places (e.g. {}); {} rows of earlier imports "
                            + "removed, {} hidden (still referenced)", city.slug(), prepared.unrealisticIds().size(),
                    prepared.unrealisticExamples().stream().limit(5).toList(), unrealistic.removed(), unrealistic.hidden());
        }

        String[] keptIds = places.stream().map(OsmPlace::osmId).toArray(String[]::new);
        transactions.executeWithoutResult(status -> {
            jdbc.update(ASSIGN_CITY, city.id());
            jdbc.update(con -> {
                var ps = con.prepareStatement(ASSIGN_CITY_OUTSIDE_POLYGONS);
                ps.setLong(1, city.id());
                ps.setArray(2, con.createArrayOf("text", keptIds));
                return ps;
            });
        });
        int inCity = count("SELECT count(*) FROM places WHERE city_id = ?", city.id());
        int withDistrict = areaImporter.assignPlaceDistricts(city.id()).withDistrict();

        return new ImportResult(city.slug(), elements.size(), inserted, updated, prepared.skippedDuplicates(),
                prepared.skippedUnusable(), skippedEdited, copied, prepared.osmDuplicateIds().size(), removed,
                inCity, withDistrict, prepared.unrealisticIds().size(), unrealistic.removed(), unrealistic.hidden(),
                prepared.unrealisticExamples(), null);
    }

    // Deletes the OSM rows of these elements that nothing references and hides the others
    RemovedRows removeOrHide(List<String> osmIds) {
        if (osmIds.isEmpty()) {
            return new RemovedRows(0, 0);
        }
        String[] ids = osmIds.toArray(String[]::new);
        return transactions.execute(status -> {
            int deleted = jdbc.update(con -> {
                var ps = con.prepareStatement(DELETE_UNREFERENCED);
                ps.setArray(1, con.createArrayOf("text", ids));
                return ps;
            });
            int hidden = jdbc.update(con -> {
                var ps = con.prepareStatement(HIDE);
                ps.setArray(1, con.createArrayOf("text", ids));
                return ps;
            });
            return new RemovedRows(deleted, hidden);
        });
    }

    record RemovedRows(int removed, int hidden) {
    }

    // Maps elements to places, drops unusable ones and duplicates of verified places (no database writes)
    static Prepared prepare(List<OverpassResponse.Element> elements, OsmDeduplicator deduplicator) {
        // The same osm_id twice in one batch would make ON CONFLICT fail
        Map<String, OsmPlace> places = new LinkedHashMap<>();
        Map<Long, VerifiedMedia> media = new LinkedHashMap<>();
        int unusable = 0;
        int duplicates = 0;
        Set<String> unrealisticIds = new LinkedHashSet<>();
        List<String> unrealisticExamples = new ArrayList<>();
        for (OverpassResponse.Element element : elements) {
            OsmPlace place = OsmPlaceMapper.map(element);
            String unrealistic = place == null ? null : PlaceRealismFilter.rejectElement(element, place);
            if (place == null) {
                unusable++;
                // No longer a kind we show (a cemetery once filed as a place of worship, a municipal market once a
                // sight): the row an earlier import wrote for it goes, like an unrealistic one
                if (element.type() != null) {
                    unrealisticIds.add(element.type() + "/" + element.id());
                }
            } else if (unrealistic != null) {
                // A school canteen, a police club, a closed or private place, a generic "Kafe"
                unrealisticIds.add(place.osmId());
                if (unrealisticExamples.size() < MAX_EXAMPLES) {
                    unrealisticExamples.add(place.name() + " (" + unrealistic + ")");
                }
            } else {
                Optional<OsmDeduplicator.ExistingPlace> verified = deduplicator.findDuplicateOf(place);
                if (verified.isPresent()) {
                    duplicates++;
                    // The verified place may still get this element's photo reference (first match wins)
                    OsmDeduplicator.ExistingPlace target = verified.get();
                    if (target.id() != null && !target.hasMedia() && place.hasMedia()) {
                        media.putIfAbsent(target.id(),
                                new VerifiedMedia(target.id(), place.wikidata(), place.commonsFile()));
                    }
                } else {
                    places.putIfAbsent(place.osmId(), place);
                }
            }
        }
        // The same park / cafe mapped twice in OSM becomes one place
        OsmDeduplicator.Deduped deduped = OsmDeduplicator.dedupeAmongThemselves(new ArrayList<>(places.values()));
        return new Prepared(new ArrayList<>(deduped.kept()), duplicates, unusable, new ArrayList<>(media.values()),
                deduped.droppedOsmIds(), new ArrayList<>(unrealisticIds), unrealisticExamples);
    }

    private ChunkResult writeChunk(List<OsmPlace> chunk) {
        String[] osmIds = chunk.stream().map(OsmPlace::osmId).toArray(String[]::new);

        // Which of these already exist, and are they still plain OSM rows?
        Map<String, String> existingSources = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT osm_id, source FROM places WHERE osm_id = ANY (?)");
            ps.setArray(1, con.createArrayOf("text", osmIds));
            return ps;
        }, rs -> {
            existingSources.put(rs.getString("osm_id"), rs.getString("source"));
        });

        List<OsmPlace> writable = chunk.stream()
                .filter(p -> !existingSources.containsKey(p.osmId()) || Place.OSM_SOURCE.equals(existingSources.get(p.osmId())))
                .toList();
        int updated = (int) writable.stream().filter(p -> existingSources.containsKey(p.osmId())).count();

        jdbc.batchUpdate(UPSERT, writable, CHUNK_SIZE, (ps, p) -> {
            ps.setString(1, p.name());
            ps.setString(2, p.nameEn());
            ps.setString(3, p.cuisine());
            ps.setDouble(4, p.longitude());
            ps.setDouble(5, p.latitude());
            ps.setString(6, p.category().name());
            ps.setBoolean(7, p.indoor());
            Array tags = ps.getConnection().createArrayOf("text", p.tags().toArray());
            ps.setArray(8, tags);
            ps.setString(9, p.sourceUrl());
            ps.setString(10, p.osmId());
            ps.setString(11, p.address());
            ps.setString(12, p.neighborhood());
            ps.setString(13, p.wikidata());
            ps.setString(14, p.commonsFile());
            ps.setBoolean(15, p.stale());
        });

        // Opening hours: replace whatever the previous import wrote
        Map<String, Long> ids = new HashMap<>();
        String[] writableIds = writable.stream().map(OsmPlace::osmId).toArray(String[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, osm_id FROM places WHERE osm_id = ANY (?) AND source = 'OSM'");
            ps.setArray(1, con.createArrayOf("text", writableIds));
            return ps;
        }, rs -> {
            ids.put(rs.getString("osm_id"), rs.getLong("id"));
        });

        Long[] placeIds = ids.values().toArray(Long[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM place_opening_hours WHERE place_id = ANY (?)");
            ps.setArray(1, con.createArrayOf("bigint", placeIds));
            return ps;
        });

        List<HoursRow> hours = new ArrayList<>();
        for (OsmPlace place : writable) {
            Long id = ids.get(place.osmId());
            if (id != null) {
                place.openingHours().forEach(h -> hours.add(new HoursRow(id, h)));
            }
        }
        jdbc.batchUpdate("INSERT INTO place_opening_hours (place_id, day_of_week, opens_at, closes_at) VALUES (?, ?, ?, ?)",
                hours, 1000, (ps, row) -> {
                    ps.setLong(1, row.placeId());
                    ps.setShort(2, (short) row.hours().dayOfWeek());
                    ps.setTime(3, Time.valueOf(row.hours().opensAt()));
                    ps.setTime(4, Time.valueOf(row.hours().closesAt()));
                });

        return new ChunkResult(writable.size() - updated, updated, chunk.size() - writable.size());
    }

    /**
     * @param verifiedMedia photo references (wikidata / Commons file) of skipped duplicates, for verified
     *                      places that have none yet
     */
    record Prepared(List<OsmPlace> places, int skippedDuplicates, int skippedUnusable,
                    List<VerifiedMedia> verifiedMedia, List<String> osmDuplicateIds, List<String> unrealisticIds,
                    List<String> unrealisticExamples) {
    }

    record VerifiedMedia(long placeId, String wikidata, String commonsFile) {
    }

    private record ChunkResult(int inserted, int updated, int skippedEdited) {
    }

    private record HoursRow(long placeId, OpeningHoursDto hours) {
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /**
     * @param city              the city's slug
     * @param fetched           elements Overpass returned
     * @param inserted          new OSM places
     * @param updated           existing OSM places refreshed
     * @param skippedDuplicates same place as a verified one nearby
     * @param skippedUnusable   no name / coordinates / known kind
     * @param skippedEdited     OSM rows an admin has taken over (source changed); left as they are
     * @param verifiedMediaCopied verified places that got a duplicate's wikidata / Commons file (photo only)
     * @param osmDuplicatesMerged  OSM elements dropped as another element of the same place
     * @param duplicateRowsRemoved rows of earlier imports for those elements that were deleted (unreferenced)
     * @param placesInCity         the city's places (verified + OSM) after the import
     * @param placesWithDistrict   the city's places with a district after the import
     * @param unrealistic          elements dropped by PlaceRealismFilter (school canteens, private / closed places, ...)
     * @param unrealisticRemoved   rows of earlier imports for them that were deleted (unreferenced)
     * @param unrealisticHidden    rows of earlier imports for them that were hidden (a route / saved place / photo uses them)
     * @param unrealisticExamples  up to MAX_EXAMPLES of them: "name (reason)"
     * @param areas                the district / neighbourhood import that ran first; null if it failed
     */
    public record ImportResult(String city, int fetched, int inserted, int updated, int skippedDuplicates,
                               int skippedUnusable, int skippedEdited, int verifiedMediaCopied,
                               int osmDuplicatesMerged, int duplicateRowsRemoved, int placesInCity,
                               int placesWithDistrict, int unrealistic, int unrealisticRemoved,
                               int unrealisticHidden, List<String> unrealisticExamples,
                               OsmAreaImporter.AreaImportResult areas) {

        ImportResult withAreas(OsmAreaImporter.AreaImportResult areas) {
            return new ImportResult(city, fetched, inserted, updated, skippedDuplicates, skippedUnusable,
                    skippedEdited, verifiedMediaCopied, osmDuplicatesMerged, duplicateRowsRemoved, placesInCity,
                    placesWithDistrict, unrealistic, unrealisticRemoved, unrealisticHidden, unrealisticExamples, areas);
        }
    }
}

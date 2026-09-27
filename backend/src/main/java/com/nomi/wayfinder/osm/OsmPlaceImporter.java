package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.dto.OpeningHoursDto;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.osm.OsmPlaceMapper.OsmPlace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Imports / refreshes Istanbul places from OpenStreetMap.
 *
 * Rows are upserted by osm_id with source = 'OSM'. Price, rating, description and visit length
 * stay NULL (OSM does not have them; we never invent them). Rows that disappeared from OSM are
 * NOT deleted: route stops and saved places may reference them. An OSM element that duplicates a
 * verified (non-OSM) place is skipped; the verified place only receives the element's wikidata /
 * Commons file when it has neither (for its photo), nothing else. An OSM row an admin
 * has since edited (source no longer 'OSM') is left alone as well.
 * wikidata / commons_file are refreshed on every run, so rows imported before they existed get them too.
 *
 * Plain JDBC batches in chunks of CHUNK_SIZE, each chunk in its own transaction, so ~12k rows take
 * seconds and a failure only loses the current chunk.
 */
@Service
public class OsmPlaceImporter {

    private static final Logger log = LoggerFactory.getLogger(OsmPlaceImporter.class);
    static final int CHUNK_SIZE = 500;

    private static final String UPSERT = """
            INSERT INTO places (name, location, category, indoor, tags, source, source_url, osm_id,
                                address, neighborhood, wikidata, commons_file, created_at, updated_at)
            VALUES (?, CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography), ?, ?, ?, 'OSM', ?, ?, ?, ?, ?, ?, now(), now())
            ON CONFLICT (osm_id) DO UPDATE SET
                -- A new wikidata id / Commons file means the photo must be looked up again
                image_checked_at = CASE
                    WHEN places.wikidata IS DISTINCT FROM EXCLUDED.wikidata
                      OR places.commons_file IS DISTINCT FROM EXCLUDED.commons_file THEN NULL
                    ELSE places.image_checked_at END,
                wikidata = EXCLUDED.wikidata,
                commons_file = EXCLUDED.commons_file,
                name = EXCLUDED.name,
                location = EXCLUDED.location,
                category = EXCLUDED.category,
                indoor = EXCLUDED.indoor,
                tags = EXCLUDED.tags,
                source_url = EXCLUDED.source_url,
                address = EXCLUDED.address,
                neighborhood = EXCLUDED.neighborhood,
                updated_at = now()
            WHERE places.source = 'OSM'
            """;

    // Only the two photo references, only onto a verified place that has neither yet
    private static final String COPY_MEDIA_TO_VERIFIED = """
            UPDATE places SET wikidata = ?, commons_file = ?
            WHERE id = ? AND source <> 'OSM' AND wikidata IS NULL AND commons_file IS NULL
            """;

    private final OverpassClient overpassClient;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OsmPlaceImporter(OverpassClient overpassClient, JdbcTemplate jdbc, TransactionTemplate transactions,
                            ApplicationEventPublisher events) {
        this.overpassClient = overpassClient;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.events = events;
    }

    public boolean hasOsmPlaces() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM places WHERE source = 'OSM')", Boolean.class));
    }

    public boolean isRunning() {
        return running.get();
    }

    // Downloads and imports; one run at a time (409 when one is already running)
    public ImportResult importIstanbul() {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(HttpStatus.CONFLICT, "An OSM import is already running");
        }
        try {
            long started = System.currentTimeMillis();
            log.info("OSM import: downloading Istanbul places from Overpass...");
            List<OverpassResponse.Element> elements = overpassClient.fetchIstanbulPlaces();
            log.info("OSM import: {} elements downloaded, writing to the database...", elements.size());

            ImportResult result = importElements(elements);
            log.info("OSM import finished in {} s: {}", (System.currentTimeMillis() - started) / 1000, result);
            // Photos for new / changed wikidata and Commons references (WikimediaImageJobs listens)
            events.publishEvent(new OsmImportFinishedEvent(result));
            return result;
        } finally {
            running.set(false);
        }
    }

    ImportResult importElements(List<OverpassResponse.Element> elements) {
        OsmDeduplicator deduplicator = new OsmDeduplicator(jdbc.query("""
                        SELECT id, name, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon,
                               (wikidata IS NOT NULL OR commons_file IS NOT NULL) AS has_media
                        FROM places WHERE source <> 'OSM'
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

        return new ImportResult(elements.size(), inserted, updated, prepared.skippedDuplicates(),
                prepared.skippedUnusable(), skippedEdited, copied);
    }

    // Maps elements to places, drops unusable ones and duplicates of verified places (no database writes)
    static Prepared prepare(List<OverpassResponse.Element> elements, OsmDeduplicator deduplicator) {
        // The same osm_id twice in one batch would make ON CONFLICT fail
        Map<String, OsmPlace> places = new LinkedHashMap<>();
        Map<Long, VerifiedMedia> media = new LinkedHashMap<>();
        int unusable = 0;
        int duplicates = 0;
        for (OverpassResponse.Element element : elements) {
            OsmPlace place = OsmPlaceMapper.map(element);
            if (place == null) {
                unusable++;
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
        return new Prepared(new ArrayList<>(places.values()), duplicates, unusable, new ArrayList<>(media.values()));
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
            ps.setDouble(2, p.longitude());
            ps.setDouble(3, p.latitude());
            ps.setString(4, p.category().name());
            ps.setBoolean(5, p.indoor());
            Array tags = ps.getConnection().createArrayOf("text", p.tags().toArray());
            ps.setArray(6, tags);
            ps.setString(7, p.sourceUrl());
            ps.setString(8, p.osmId());
            ps.setString(9, p.address());
            ps.setString(10, p.neighborhood());
            ps.setString(11, p.wikidata());
            ps.setString(12, p.commonsFile());
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
                    List<VerifiedMedia> verifiedMedia) {
    }

    record VerifiedMedia(long placeId, String wikidata, String commonsFile) {
    }

    private record ChunkResult(int inserted, int updated, int skippedEdited) {
    }

    private record HoursRow(long placeId, OpeningHoursDto hours) {
    }

    /**
     * @param fetched           elements Overpass returned
     * @param inserted          new OSM places
     * @param updated           existing OSM places refreshed
     * @param skippedDuplicates same place as a verified one nearby
     * @param skippedUnusable   no name / coordinates / known kind
     * @param skippedEdited     OSM rows an admin has taken over (source changed); left as they are
     * @param verifiedMediaCopied verified places that got a duplicate's wikidata / Commons file (photo only)
     */
    public record ImportResult(int fetched, int inserted, int updated, int skippedDuplicates,
                               int skippedUnusable, int skippedEdited, int verifiedMediaCopied) {
    }
}

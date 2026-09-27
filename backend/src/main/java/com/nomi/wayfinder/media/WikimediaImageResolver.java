package com.nomi.wayfinder.media;

import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.media.WikimediaParser.CommonsImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds one free-licensed Wikimedia Commons photo per place that has a wikidata id or a Commons file
 * (stored by the OSM import) and was not checked yet, or was checked more than recheck-after ago.
 *
 * 1. wikidata id -> the item's image (P18) through the Wikidata API
 * 2. file -> 800 px thumbnail, author, license and file page through the Commons API
 *    (the OSM Commons file is tried first, then the Wikidata image; the first usable one wins)
 * 3. image_checked_at is set even when nothing usable was found, so we do not ask again every day.
 *    Places whose lookup failed (network, busy servers) are left unchecked and retried on the next run.
 *
 * Only real API answers are stored; nothing is guessed. Writes use JDBC so JPA saves never overwrite them.
 */
@Service
public class WikimediaImageResolver {

    private static final Logger log = LoggerFactory.getLogger(WikimediaImageResolver.class);

    private static final String PENDING = """
            SELECT id, wikidata, commons_file FROM places
            WHERE (wikidata IS NOT NULL OR commons_file IS NOT NULL)
              AND (image_checked_at IS NULL OR image_checked_at < now() - CAST(? AS double precision) * interval '1 second')
            ORDER BY image_checked_at NULLS FIRST, id
            LIMIT ?
            """;

    private static final String WRITE = """
            UPDATE places SET image_url = ?, image_author = ?, image_license = ?, image_source_url = ?,
                              image_checked_at = now()
            WHERE id = ?
            """;

    // A photo whose wikidata / Commons reference is gone (e.g. removed in OSM) no longer belongs to the place
    private static final String CLEAR_ORPHANED = """
            UPDATE places SET image_url = NULL, image_author = NULL, image_license = NULL, image_source_url = NULL
            WHERE image_url IS NOT NULL AND wikidata IS NULL AND commons_file IS NULL
            """;

    private final WikimediaClient client;
    private final JdbcTemplate jdbc;
    private final NomiProperties.Images properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WikimediaImageResolver(WikimediaClient client, JdbcTemplate jdbc, NomiProperties nomiProperties) {
        this.client = client;
        this.jdbc = jdbc;
        this.properties = nomiProperties.images();
    }

    public boolean isRunning() {
        return running.get();
    }

    // One pass at a time (409 when one is already running)
    public ResolveResult resolve() {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(HttpStatus.CONFLICT, "A place image lookup is already running");
        }
        try {
            long started = System.currentTimeMillis();
            int orphaned = jdbc.update(CLEAR_ORPHANED);
            if (orphaned > 0) {
                log.info("Place images: removed {} images whose wikidata / Commons reference is gone", orphaned);
            }

            List<PendingPlace> pending = jdbc.query(PENDING,
                    (rs, i) -> new PendingPlace(rs.getLong("id"), rs.getString("wikidata"), rs.getString("commons_file")),
                    properties.recheckAfter().toSeconds(), Math.max(1, properties.maxPlacesPerRun()));
            if (pending.isEmpty()) {
                log.info("Place images: nothing to check");
                return new ResolveResult(0, 0, 0, 0);
            }
            log.info("Place images: checking {} places...", pending.size());

            // 1. Wikidata items -> image file names
            Map<String, String> wikidataFiles = new HashMap<>();
            Set<String> failedIds = new HashSet<>();
            List<String> ids = pending.stream().map(PendingPlace::wikidata).filter(Objects::nonNull).distinct().toList();
            for (List<String> batch : batches(ids)) {
                try {
                    wikidataFiles.putAll(client.fetchImageFiles(batch));
                } catch (RuntimeException e) {
                    log.warn("Place images: Wikidata lookup of {} items failed, retried next run: {}",
                            batch.size(), e.getMessage());
                    failedIds.addAll(batch);
                }
            }

            // 2. Commons files -> usable images
            Map<String, CommonsImage> images = new HashMap<>();
            Set<String> failedFiles = new HashSet<>();
            List<String> files = pending.stream()
                    .flatMap(p -> candidates(p, wikidataFiles).stream())
                    .distinct()
                    .toList();
            for (List<String> batch : batches(files)) {
                try {
                    images.putAll(client.fetchImages(batch));
                } catch (RuntimeException e) {
                    log.warn("Place images: Commons lookup of {} files failed, retried next run: {}",
                            batch.size(), e.getMessage());
                    failedFiles.addAll(batch);
                }
            }

            // 3. Store
            List<Outcome> outcomes = decide(pending, wikidataFiles, failedIds, images, failedFiles);
            List<Outcome> checked = outcomes.stream().filter(o -> !o.failed()).toList();
            jdbc.batchUpdate(WRITE, checked, 500, (ps, o) -> {
                CommonsImage image = o.image();
                ps.setString(1, image == null ? null : image.url());
                ps.setString(2, image == null ? null : image.author());
                ps.setString(3, image == null ? null : image.license());
                ps.setString(4, image == null ? null : image.sourceUrl());
                ps.setLong(5, o.placeId());
            });

            int withImage = (int) checked.stream().filter(o -> o.image() != null).count();
            ResolveResult result = new ResolveResult(pending.size(), withImage, checked.size() - withImage,
                    outcomes.size() - checked.size());
            log.info("Place images finished in {} s: {}", (System.currentTimeMillis() - started) / 1000, result);
            return result;
        } finally {
            running.set(false);
        }
    }

    /**
     * Per place: the first usable candidate file wins. Without one, the place counts as checked
     * (image cleared) unless one of its lookups failed; then it is left for the next run.
     */
    static List<Outcome> decide(List<PendingPlace> pending, Map<String, String> wikidataFiles, Set<String> failedIds,
                                Map<String, CommonsImage> images, Set<String> failedFiles) {
        List<Outcome> outcomes = new ArrayList<>();
        for (PendingPlace place : pending) {
            List<String> candidates = candidates(place, wikidataFiles);
            CommonsImage image = candidates.stream().map(images::get).filter(Objects::nonNull).findFirst().orElse(null);
            boolean failed = image == null
                    && ((place.wikidata() != null && failedIds.contains(place.wikidata()))
                    || candidates.stream().anyMatch(failedFiles::contains));
            outcomes.add(new Outcome(place.id(), image, failed));
        }
        return outcomes;
    }

    // The OSM Commons file first, then the Wikidata image; photo formats only (svg, tif, pdf ... never qualify)
    static List<String> candidates(PendingPlace place, Map<String, String> wikidataFiles) {
        List<String> files = new ArrayList<>();
        if (place.commonsFile() != null) {
            files.add(place.commonsFile());
        }
        String fromWikidata = place.wikidata() == null ? null : wikidataFiles.get(place.wikidata());
        if (fromWikidata != null && !files.contains(fromWikidata)) {
            files.add(fromWikidata);
        }
        return files.stream().filter(WikimediaParser::isImageFile).toList();
    }

    private static <T> List<List<T>> batches(List<T> items) {
        List<List<T>> batches = new ArrayList<>();
        for (int from = 0; from < items.size(); from += WikimediaClient.BATCH_SIZE) {
            batches.add(items.subList(from, Math.min(items.size(), from + WikimediaClient.BATCH_SIZE)));
        }
        return batches;
    }

    record PendingPlace(long id, String wikidata, String commonsFile) {
    }

    // image null + failed false = checked, nothing usable
    record Outcome(long placeId, CommonsImage image, boolean failed) {
    }

    /**
     * @param checked      places looked at in this pass
     * @param withImage    now have a photo
     * @param withoutImage no usable (free-licensed photo) image found; checked again after recheck-after
     * @param failed       lookup failed (network / busy servers); retried on the next pass
     */
    public record ResolveResult(int checked, int withImage, int withoutImage, int failed) {
    }
}

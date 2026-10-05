package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies PlaceRealismFilter's name rules (and PlaceTags.placeToPray for places of worship) to the OSM and Overture
 * rows already in the database (their OSM tags are not stored, so access / lifecycle tags are only checked by the
 * next import of the city). Flagged rows that no route stop,
 * saved place or user photo references are deleted; the others are hidden. Hand-verified places are never touched.
 *
 * Runs on POST /api/v1/admin/places/cleanup (and after startup only when nomi.places.cleanup-on-startup=true: the
 * one-off cleanup has run; since website / phone / brand are not stored, a startup pass could remove a business the
 * last import kept for those signs). OSM rows with opening hours and rows with a Wikidata item are never touched here.
 */
@Service
public class PlaceRealismCleanup {

    private static final Logger log = LoggerFactory.getLogger(PlaceRealismCleanup.class);
    static final int MAX_EXAMPLES = 20;

    private final JdbcTemplate jdbc;
    private final PlaceRemoval removal;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean cleanupOnStartup;

    public PlaceRealismCleanup(JdbcTemplate jdbc, PlaceRemoval removal, ApplicationEventPublisher events,
                               @Value("${nomi.places.cleanup-on-startup:false}") boolean cleanupOnStartup) {
        this.cleanupOnStartup = cleanupOnStartup;
        this.jdbc = jdbc;
        this.removal = removal;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!cleanupOnStartup) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                run();
            } catch (Exception e) {
                log.warn("Place cleanup after startup failed: {}", e.getMessage());
            }
        }, "place-cleanup");
        thread.setDaemon(true);
        thread.start();
    }

    public CleanupResult run() {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(HttpStatus.CONFLICT, "A place cleanup is already running");
        }
        try {
            List<Long> flagged = new ArrayList<>();
            List<String> examples = new ArrayList<>();
            int[] checked = {0};
            // Rows with opening hours or a Wikidata item show signs of a public business (see
            // PlaceRealismFilter.hasPublicBusinessSigns; website / phone / brand are not stored, the import checks them)
            jdbc.query("""
                            SELECT p.id, p.source, p.name, p.category FROM places p
                            WHERE NOT p.hidden AND p.wikidata IS NULL
                              AND (p.source = 'OVERTURE' OR p.source = 'OSM' AND p.osm_id IS NOT NULL
                                   AND NOT EXISTS (SELECT 1 FROM place_opening_hours h WHERE h.place_id = p.id))
                            """,
                    rs -> {
                        checked[0]++;
                        String name = rs.getString("name");
                        PlaceCategory category = PlaceCategory.valueOf(rs.getString("category"));
                        String reason = PlaceRealismFilter.rejectName(name, category);
                        // Overture (Meta pages) must say "Camii", "Kilisesi", ... (OvertureMapper)
                        if (reason == null && category == PlaceCategory.WORSHIP
                                && !PlaceTags.placeToPray(name, "OVERTURE".equals(rs.getString("source")))) {
                            reason = "not a place to pray";
                        }
                        if (reason != null) {
                            flagged.add(rs.getLong("id"));
                            if (examples.size() < MAX_EXAMPLES) {
                                examples.add(name + " (" + reason + ")");
                            }
                        }
                    });
            // Deleted when nothing references them, else hidden (a route stop, saved place or user photo uses them)
            PlaceRemoval.RemovedRows rows = removal.removeOrHideByIds(flagged);
            CleanupResult result = new CleanupResult(checked[0], flagged.size(), rows.removed(), rows.hidden(), examples);
            log.info("Place cleanup: {}", result);
            if (!flagged.isEmpty()) {
                events.publishEvent(new PlacesChangedEvent("cleanup"));
            }
            return result;
        } finally {
            running.set(false);
        }
    }

    /**
     * @param checked  visible OSM / Overture rows looked at
     * @param flagged  rows whose name is not a realistic place
     * @param removed  of those, deleted (nothing referenced them)
     * @param hidden   of those, hidden (a route stop, saved place or user photo uses them)
     * @param examples up to MAX_EXAMPLES flagged names with the reason
     */
    public record CleanupResult(int checked, int flagged, int removed, int hidden, List<String> examples) {
    }
}

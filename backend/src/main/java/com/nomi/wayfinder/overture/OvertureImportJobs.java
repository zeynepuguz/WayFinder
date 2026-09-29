package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.osm.OsmImportFinishedEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the Overture import (OverturePlaceImporter) city by city on one background thread:
 * - after each city's OSM import (OsmImportFinishedEvent), so new OSM places are matched right away
 * - after startup (nomi.overture.import-on-startup): every city never imported from Overture. OSM places are not
 *   required: where Overpass never delivered a city, Overture still brings its food places (districts are assigned
 *   when the OSM import of the city runs)
 * - on the refresh cron (nomi.overture.refresh-cron): cities whose Overture release is not the latest
 * - by admins: POST /api/v1/admin/places/import-overture (?city=slug waits; without it all cities are queued)
 * Imports never run in parallel (one lock for the queue and admin runs); a failing city is logged and skipped.
 */
@Component
public class OvertureImportJobs {

    private static final Logger log = LoggerFactory.getLogger(OvertureImportJobs.class);
    static final int RECENT_RESULTS = 20;
    // The catalog is asked at most this often during a run
    static final Duration RELEASE_CACHE = Duration.ofHours(1);

    private final OverturePlaceImporter importer;
    private final OvertureProperties properties;
    private final JdbcTemplate jdbc;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "overture-import");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> pending = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Deque<String> recent = new ArrayDeque<>();
    private volatile String current;
    private volatile String cachedRelease;
    private volatile Instant releaseCheckedAt;

    public OvertureImportJobs(OverturePlaceImporter importer, OvertureProperties properties, JdbcTemplate jdbc) {
        this.importer = importer;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importOnStartup() {
        if (properties.enabled() && properties.importOnStartup()) {
            enqueue(jdbc.queryForList("""
                    SELECT slug FROM cities
                    WHERE overture_imported_at IS NULL AND south IS NOT NULL
                    ORDER BY (slug = 'istanbul') DESC, slug
                    """, String.class), "startup");
        }
    }

    @EventListener
    public void onOsmImport(OsmImportFinishedEvent event) {
        if (properties.enabled() && event.result() != null) {
            enqueue(List.of(event.result().city()), "after OSM import");
        }
    }

    @Scheduled(cron = "${nomi.overture.refresh-cron:-}", zone = "${nomi.timezone:Europe/Istanbul}")
    public void monthlyRefresh() {
        if (!properties.enabled()) {
            return;
        }
        try {
            String latest = release(true);
            enqueue(jdbc.queryForList("""
                    SELECT slug FROM cities
                    WHERE south IS NOT NULL AND overture_release IS DISTINCT FROM ?
                    ORDER BY (slug = 'istanbul') DESC, slug
                    """, String.class, latest), "refresh to " + latest);
        } catch (RuntimeException e) {
            log.warn("Overture refresh skipped: {}", e.getMessage());
        }
    }

    // Admin: every city (with a known bounding box), in the background
    public Status enqueueAll() {
        requireEnabled();
        enqueue(jdbc.queryForList("""
                SELECT slug FROM cities WHERE south IS NOT NULL
                ORDER BY (slug = 'istanbul') DESC, slug
                """, String.class), "admin");
        return status();
    }

    // Admin: one city now (the caller waits; runs after the city being imported in the background, if any)
    public OverturePlaceImporter.ImportResult importNow(String slug) {
        requireEnabled();
        OverturePlaceImporter.CityBox city = importer.findCity(slug)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + slug));
        lock.lock();
        try {
            current = slug;
            OverturePlaceImporter.ImportResult result = importer.importCity(city, release(true));
            remember(slug + ": " + result.added() + " added, " + result.unconfirmed() + " unconfirmed");
            pending.remove(slug);
            return result;
        } catch (IllegalStateException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Overture Maps is not available right now: " + e.getMessage());
        } finally {
            current = null;
            lock.unlock();
        }
    }

    public Status status() {
        synchronized (recent) {
            return new Status(current, new ArrayList<>(pending), new ArrayList<>(recent), cachedRelease);
        }
    }

    /**
     * @param current  city being imported now (null = idle)
     * @param pending  cities waiting
     * @param recent   last results, newest first ("kocaeli: 812 added, 95 unconfirmed" / "... failed: ...")
     * @param release  the latest Overture release seen
     */
    public record Status(String current, List<String> pending, List<String> recent, String release) {
    }

    private void enqueue(List<String> slugs, String trigger) {
        List<String> fresh = slugs.stream().filter(pending::add).toList();
        if (fresh.isEmpty()) {
            return;
        }
        log.info("Overture import ({}): {} cities queued", trigger, fresh.size());
        for (String slug : fresh) {
            executor.submit(() -> runQueued(slug));
        }
    }

    private void runQueued(String slug) {
        if (!pending.contains(slug)) {
            // Imported by an admin in the meantime
            return;
        }
        lock.lock();
        try {
            pending.remove(slug);
            current = slug;
            Optional<OverturePlaceImporter.CityBox> city = importer.findCity(slug);
            if (city.isEmpty()) {
                return;
            }
            OverturePlaceImporter.ImportResult result = importer.importCity(city.get(), release(false));
            remember(slug + ": " + result.added() + " added, " + result.unconfirmed() + " unconfirmed");
            Thread.sleep(properties.cityDelay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Overture import ({}) failed: {}", slug, e.getMessage());
            remember(slug + " failed: " + e.getMessage());
        } finally {
            current = null;
            lock.unlock();
        }
    }

    private String release(boolean fresh) {
        Instant now = Instant.now();
        if (fresh || cachedRelease == null || releaseCheckedAt.plus(RELEASE_CACHE).isBefore(now)) {
            cachedRelease = importer.latestRelease();
            releaseCheckedAt = now;
        }
        return cachedRelease;
    }

    private void remember(String line) {
        synchronized (recent) {
            recent.addFirst(line);
            while (recent.size() > RECENT_RESULTS) {
                recent.removeLast();
            }
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BusinessException(HttpStatus.CONFLICT, "Overture import is disabled (nomi.overture.enabled)");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

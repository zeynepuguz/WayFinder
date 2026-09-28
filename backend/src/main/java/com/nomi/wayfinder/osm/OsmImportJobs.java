package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the OpenStreetMap import city by city, one run at a time:
 * - once after startup, in the background (nomi.osm.import-on-startup): the provinces when they are missing, then
 *   every selected city that was never imported
 * - on the refresh cron (nomi.osm.refresh-cron, monthly by default): provinces, then every selected city not
 *   imported within nomi.osm.refresh-after
 * - by admins: POST /api/v1/admin/places/import-osm (all cities, in the background; ?city=slug = one city, waits)
 *
 * Order: Istanbul first, then by the OSM population tag (largest first), then alphabetically. Overpass calls are
 * separated by nomi.osm.call-delay. A failing city is logged and skipped; the next run tries it again (resumable:
 * cities imported within the refresh window are skipped unless the run is forced). Place photos are looked up
 * after each city (WikimediaImageJobs listens to OsmImportFinishedEvent).
 */
@Component
public class OsmImportJobs {

    private static final Logger log = LoggerFactory.getLogger(OsmImportJobs.class);
    static final String FIRST_CITY = "istanbul";

    private final OsmCityImporter cityImporter;
    private final NomiProperties.Osm properties;
    private final Clock clock;
    // One daemon thread; closed with the application context (devtools restarts must not leave old runs behind)
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "osm-import");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile ImportStatus status = ImportStatus.idle();

    public OsmImportJobs(OsmCityImporter cityImporter, NomiProperties nomiProperties, Clock clock) {
        this.cityImporter = cityImporter;
        this.properties = nomiProperties.osm();
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importOnStartup() {
        if (properties == null || !properties.importOnStartup()) {
            return;
        }
        try {
            startAll("startup", false, true);
        } catch (BusinessException e) {
            log.info("OSM import on startup skipped: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${nomi.osm.refresh-cron:-}", zone = "${nomi.timezone:Europe/Istanbul}")
    public void monthlyRefresh() {
        try {
            startAll("scheduled refresh", false, false);
        } catch (BusinessException e) {
            log.info("OSM refresh skipped: {}", e.getMessage());
        }
    }

    /**
     * Starts the import of every selected city in the background (409 when a run is going on).
     *
     * @param force        also re-import cities imported within the refresh window
     * @param onlyMissing  startup mode: provinces only when missing, cities only when never imported
     */
    public ImportStatus startAll(String trigger, boolean force, boolean onlyMissing) {
        acquire();
        cancelRequested.set(false);
        status = ImportStatus.started(trigger, clock.instant());
        try {
            executor.submit(() -> {
                try {
                    runAll(trigger, force, onlyMissing);
                } catch (Exception e) {
                    log.warn("OSM import ({}) failed: {}", trigger, e.getMessage());
                    status = status.withError(e.getMessage());
                } finally {
                    status = status.finished(clock.instant(), cancelRequested.get());
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return status;
    }

    /**
     * Imports one city now (the caller waits; 409 when another run is going on). Downloads the provinces first
     * when the city is not known yet.
     */
    public OsmCityImporter.CityImportResult importOne(String slug) {
        acquire();
        cancelRequested.set(false);
        String trigger = "admin: " + slug;
        status = ImportStatus.started(trigger, clock.instant());
        try {
            Optional<OsmCityImporter.CityRow> city = cityImporter.findCity(slug);
            if (city.isEmpty() && cityImporter.cityCount() < OsmCityImporter.EXPECTED_CITIES) {
                status = status.withProvinces(cityImporter.importProvinces());
                pause();
                city = cityImporter.findCity(slug);
            }
            OsmCityImporter.CityRow row = city.orElseThrow(() -> new ResourceNotFoundException("City not found: " + slug));
            status = status.withPlan(List.of(row.slug())).withCurrent(row.slug());
            OsmCityImporter.CityImportResult result;
            try {
                result = cityImporter.importCity(row.toOsmCity(), this::pause);
            } catch (IllegalStateException e) {
                // Every Overpass server failed / was busy: not our bug, try again later
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "OpenStreetMap (Overpass) is not available right now: " + e.getMessage());
            }
            status = status.withDone(CityStatus.of(row.slug(), result));
            return result;
        } catch (RuntimeException e) {
            status = status.withError(e.getMessage());
            throw e;
        } finally {
            status = status.finished(clock.instant(), false);
            running.set(false);
        }
    }

    // Import of the provinces only (boundaries, label points); 409 when another run is going on
    public OsmCityImporter.ProvinceImportResult importProvinces() {
        acquire();
        status = ImportStatus.started("admin: provinces", clock.instant());
        try {
            OsmCityImporter.ProvinceImportResult result = cityImporter.importProvinces();
            status = status.withProvinces(result);
            return result;
        } catch (RuntimeException e) {
            status = status.withError(e.getMessage());
            throw e;
        } finally {
            status = status.finished(clock.instant(), false);
            running.set(false);
        }
    }

    // The running background import stops after the current city
    public ImportStatus cancel() {
        if (running.get()) {
            cancelRequested.set(true);
            log.info("OSM import: stop requested, stopping after the current city");
        }
        return status();
    }

    public ImportStatus status() {
        return status.withRunning(running.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    private void acquire() {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(HttpStatus.CONFLICT, "An OSM import is already running");
        }
    }

    private void runAll(String trigger, boolean force, boolean onlyMissing) {
        long started = System.currentTimeMillis();
        log.info("OSM import started ({}{})", trigger, force ? ", forced" : "");

        if (!onlyMissing || cityImporter.cityCount() < OsmCityImporter.EXPECTED_CITIES) {
            try {
                status = status.withProvinces(cityImporter.importProvinces());
            } catch (RuntimeException e) {
                // Known cities can still be imported
                log.warn("OSM import: province download failed, continuing with the known cities: {}", e.getMessage());
                status = status.withError("provinces: " + e.getMessage());
            }
            pause();
        }

        Instant now = clock.instant();
        List<OsmCityImporter.CityRow> plan = plan(cityImporter.listCities(), properties.selectedCities(),
                force, onlyMissing, now.minus(refreshAfter()));
        status = status.withPlan(plan.stream().map(OsmCityImporter.CityRow::slug).toList());
        log.info("OSM import: {} cities to import: {}", plan.size(),
                plan.stream().map(OsmCityImporter.CityRow::slug).toList());

        for (int i = 0; i < plan.size(); i++) {
            if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                log.info("OSM import stopped before {} ({} of {} cities done)", plan.get(i).slug(), i, plan.size());
                return;
            }
            OsmCityImporter.CityRow city = plan.get(i);
            status = status.withCurrent(city.slug());
            log.info("OSM import: city {}/{}: {}", i + 1, plan.size(), city.name());
            try {
                OsmCityImporter.CityImportResult result = cityImporter.importCity(city.toOsmCity(), this::pause);
                status = status.withDone(CityStatus.of(city.slug(), result));
            } catch (RuntimeException e) {
                // One city's failure (Overpass busy, odd data) must not stop the others
                log.warn("OSM import: {} failed, continuing with the next city: {}", city.name(), e.getMessage());
                status = status.withDone(CityStatus.failed(city.slug(), e.getMessage()));
            }
            if (i < plan.size() - 1) {
                pause();
            }
        }
        log.info("OSM import ({}) finished in {} min", trigger, (System.currentTimeMillis() - started) / 60000);
    }

    /**
     * Which cities to import, in order: Istanbul first, then by population (largest first; unknown last), then by
     * name.
     *
     * @param selected       slugs from nomi.osm.cities; null = all
     * @param onlyMissing    only cities never imported
     * @param importedBefore otherwise: only cities imported before this (unless forced)
     */
    static List<OsmCityImporter.CityRow> plan(List<OsmCityImporter.CityRow> cities, Set<String> selected,
                                              boolean force, boolean onlyMissing, Instant importedBefore) {
        return cities.stream()
                .filter(c -> selected == null || selected.contains(c.slug()))
                .filter(c -> onlyMissing ? c.placesImportedAt() == null
                        : force || c.placesImportedAt() == null || c.placesImportedAt().isBefore(importedBefore))
                .sorted(Comparator
                        .comparing((OsmCityImporter.CityRow c) -> !FIRST_CITY.equals(c.slug()))
                        .thenComparing(c -> c.population() == null ? 0L : -c.population())
                        .thenComparing(OsmCityImporter.CityRow::slug))
                .toList();
    }

    private Duration refreshAfter() {
        return properties.refreshAfter() == null ? Duration.ofDays(20) : properties.refreshAfter();
    }

    // The polite delay between two Overpass calls
    void pause() {
        Duration delay = properties.callDelay();
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelRequested.set(true);
        }
    }

    @PreDestroy
    void shutdown() {
        cancelRequested.set(true);
        executor.shutdownNow();
    }

    /**
     * GET /api/v1/admin/places/import-status.
     *
     * @param plan      slugs the run will import, in order
     * @param current   the city being imported now; null = none
     * @param cities    finished cities (imported or failed), in order
     * @param provinces the province import of this run; null = not run (yet)
     * @param errors    run-level errors (provinces, a failed single-city run)
     */
    public record ImportStatus(boolean running, String trigger, Instant startedAt, Instant finishedAt,
                               boolean cancelled, List<String> plan, String current, List<CityStatus> cities,
                               OsmCityImporter.ProvinceImportResult provinces, List<String> errors) {

        static ImportStatus idle() {
            return new ImportStatus(false, null, null, null, false, List.of(), null, List.of(), null, List.of());
        }

        static ImportStatus started(String trigger, Instant at) {
            return new ImportStatus(true, trigger, at, null, false, List.of(), null, List.of(), null, List.of());
        }

        ImportStatus withRunning(boolean value) {
            return new ImportStatus(value, trigger, startedAt, finishedAt, cancelled, plan, current, cities, provinces,
                    errors);
        }

        ImportStatus withPlan(List<String> value) {
            return new ImportStatus(running, trigger, startedAt, finishedAt, cancelled, List.copyOf(value), current,
                    cities, provinces, errors);
        }

        ImportStatus withCurrent(String value) {
            return new ImportStatus(running, trigger, startedAt, finishedAt, cancelled, plan, value, cities, provinces,
                    errors);
        }

        ImportStatus withDone(CityStatus city) {
            List<CityStatus> done = new ArrayList<>(cities);
            done.add(city);
            return new ImportStatus(running, trigger, startedAt, finishedAt, cancelled, plan, null, List.copyOf(done),
                    provinces, errors);
        }

        ImportStatus withProvinces(OsmCityImporter.ProvinceImportResult value) {
            return new ImportStatus(running, trigger, startedAt, finishedAt, cancelled, plan, current, cities, value,
                    errors);
        }

        ImportStatus withError(String error) {
            List<String> all = new ArrayList<>(errors);
            all.add(error == null ? "unknown error" : error);
            return new ImportStatus(running, trigger, startedAt, finishedAt, cancelled, plan, current, cities,
                    provinces, List.copyOf(all));
        }

        ImportStatus finished(Instant at, boolean wasCancelled) {
            return new ImportStatus(false, trigger, startedAt, at, wasCancelled, plan, null, cities, provinces, errors);
        }
    }

    /**
     * @param places    the city's places after the import (null when it failed)
     * @param districts the city's districts (null when the district import failed)
     * @param seconds   how long the city took
     * @param error     why it failed / districts failed; null = fine
     */
    public record CityStatus(String city, Integer places, Integer districts, Integer areas, Long seconds, String error) {

        static CityStatus of(String slug, OsmCityImporter.CityImportResult result) {
            var areas = result.result().areas();
            return new CityStatus(slug, result.result().placesInCity(), areas == null ? null : areas.districts(),
                    areas == null ? null : areas.areas(), result.seconds(), result.districtError());
        }

        static CityStatus failed(String slug, String error) {
            return new CityStatus(slug, null, null, null, null, error == null ? "unknown error" : error);
        }
    }
}

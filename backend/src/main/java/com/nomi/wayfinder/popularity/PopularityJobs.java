package com.nomi.wayfinder.popularity;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.osm.OsmImportFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * When place popularity is looked up by itself (one background worker, passes queued):
 * - after every OSM import (nomi.popularity.update-after-import): the places of the imported city
 * - on the cron (nomi.popularity.cron, monthly by default): every city, places checked more than recheck-after ago
 * Admins can also start it with POST /api/v1/admin/places/update-popularity.
 */
@Component
public class PopularityJobs {

    private static final Logger log = LoggerFactory.getLogger(PopularityJobs.class);
    // Queue entry for "every city"
    private static final long ALL = -1;

    private final PlacePopularityService service;
    private final PopularityProperties properties;
    private final CityService cityService;
    private final LinkedBlockingDeque<Long> queue = new LinkedBlockingDeque<>();
    private final AtomicBoolean worker = new AtomicBoolean(false);

    public PopularityJobs(PlacePopularityService service, PopularityProperties properties, CityService cityService) {
        this.service = service;
        this.properties = properties;
        this.cityService = cityService;
    }

    @EventListener
    public void afterOsmImport(OsmImportFinishedEvent event) {
        if (!properties.updateAfterImport() || event.result() == null) {
            return;
        }
        Optional<CityService.City> city = cityService.findBySlug(event.result().city());
        city.ifPresent(c -> requestPass(c.id(), "after OSM import of " + c.slug()));
    }

    @Scheduled(cron = "${nomi.popularity.cron:-}", zone = "${nomi.timezone:Europe/Istanbul}")
    public void monthlyPass() {
        requestPass(null, "scheduled");
    }

    public boolean isBusy() {
        return worker.get() || service.isRunning();
    }

    /**
     * Queues a pass (a city already waiting is not queued twice) and starts the worker when it is idle.
     *
     * @param cityId null = every city
     * @return true when a new worker was started
     */
    public boolean requestPass(Long cityId, String trigger) {
        long entry = cityId == null ? ALL : cityId;
        if (!queue.contains(entry)) {
            queue.add(entry);
        }
        if (!worker.compareAndSet(false, true)) {
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                Long next;
                while ((next = queue.poll()) != null) {
                    run(next == ALL ? null : next, trigger);
                }
            } finally {
                worker.set(false);
            }
        }, "place-popularity");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void run(Long cityId, String trigger) {
        try {
            log.info("Popularity: pass started ({}, {})", trigger, cityId == null ? "all cities" : "city " + cityId);
            service.update(cityId);
        } catch (Exception e) {
            // Unchecked places are picked up again by the next trigger
            log.warn("Popularity ({}) failed: {}", trigger, e.getMessage());
        }
    }
}

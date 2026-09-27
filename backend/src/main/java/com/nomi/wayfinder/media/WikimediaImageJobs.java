package com.nomi.wayfinder.media;

import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.osm.OsmImportFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When place photos are looked up by themselves:
 * - right after every OSM import, in the background (nomi.images.resolve-after-import)
 * - on the cron (nomi.images.resolve-cron, daily by default) for places not checked yet or due a recheck
 * Admins can also start it with POST /api/v1/admin/places/resolve-images.
 */
@Component
public class WikimediaImageJobs {

    private static final Logger log = LoggerFactory.getLogger(WikimediaImageJobs.class);

    private final WikimediaImageResolver resolver;
    private final NomiProperties.Images properties;

    public WikimediaImageJobs(WikimediaImageResolver resolver, NomiProperties nomiProperties) {
        this.resolver = resolver;
        this.properties = nomiProperties.images();
    }

    @EventListener
    public void afterOsmImport(OsmImportFinishedEvent event) {
        if (properties == null || !properties.resolveAfterImport()) {
            return;
        }
        // Own thread: an admin's import request should not also wait for the photo lookups
        Thread thread = new Thread(() -> run("after OSM import"), "place-images");
        thread.setDaemon(true);
        thread.start();
    }

    @Scheduled(cron = "${nomi.images.resolve-cron:-}", zone = "${nomi.timezone:Europe/Istanbul}")
    public void dailyPass() {
        run("scheduled");
    }

    private void run(String trigger) {
        if (resolver.isRunning()) {
            log.info("Place images ({}) skipped: a lookup is already running", trigger);
            return;
        }
        try {
            log.info("Place images: lookup started ({})", trigger);
            resolver.resolve();
        } catch (Exception e) {
            // Unchecked places are picked up again by the next trigger
            log.warn("Place images ({}) failed: {}", trigger, e.getMessage());
        }
    }
}

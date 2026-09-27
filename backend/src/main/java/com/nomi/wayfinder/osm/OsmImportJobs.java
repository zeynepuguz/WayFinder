package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.config.NomiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When the OSM import runs by itself:
 * - once after startup, in the background, if the database has no OSM places yet (nomi.osm.import-on-startup)
 * - on the refresh cron (nomi.osm.refresh-cron, monthly by default)
 * Admins can also start it with POST /api/v1/admin/places/import-osm.
 */
@Component
public class OsmImportJobs {

    private static final Logger log = LoggerFactory.getLogger(OsmImportJobs.class);

    private final OsmPlaceImporter importer;
    private final NomiProperties.Osm properties;

    public OsmImportJobs(OsmPlaceImporter importer, NomiProperties nomiProperties) {
        this.importer = importer;
        this.properties = nomiProperties.osm();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importOnStartup() {
        if (properties == null || !properties.importOnStartup()) {
            return;
        }
        if (importer.hasOsmPlaces()) {
            log.info("OSM import on startup skipped: OSM places are already in the database");
            return;
        }
        // Own thread: the download takes minutes and must not delay startup
        Thread thread = new Thread(() -> run("startup"), "osm-import");
        thread.setDaemon(true);
        thread.start();
    }

    @Scheduled(cron = "${nomi.osm.refresh-cron:-}", zone = "${nomi.timezone:Europe/Istanbul}")
    public void monthlyRefresh() {
        run("scheduled refresh");
    }

    private void run(String trigger) {
        try {
            log.info("OSM import started ({})", trigger);
            importer.importIstanbul();
        } catch (Exception e) {
            // The next trigger tries again; places already imported stay usable
            log.warn("OSM import ({}) failed: {}", trigger, e.getMessage());
        }
    }
}

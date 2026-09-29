package com.nomi.wayfinder.overture;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Overture Maps places import ("nomi.overture.*" in application.yml).
 *
 * @param enabled         false = never import (and never mark OSM places as unconfirmed)
 * @param importOnStartup in the background after startup: every city without an Overture import yet
 * @param refreshCron     Spring cron for re-importing cities whose release is older than the latest ("-" disables)
 * @param catalogUrl      Overture's STAC catalog; its "latest" field names the newest release
 * @param dataUrl         release root; %s = release ("2026-09-23.1")
 * @param minConfidence   Overture places below this certainty are not added as new places (they may still confirm
 *                        an OSM place of the same name)
 * @param memoryLimit     DuckDB memory limit ("1GB")
 * @param cityDelay       pause between two cities of a full run
 */
@ConfigurationProperties(prefix = "nomi.overture")
public record OvertureProperties(
        boolean enabled,
        boolean importOnStartup,
        String refreshCron,
        String catalogUrl,
        String dataUrl,
        double minConfidence,
        String memoryLimit,
        Duration cityDelay
) {
}

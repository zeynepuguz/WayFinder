package com.nomi.wayfinder.osm;

/**
 * Published when places changed outside an OSM import (realism cleanup, popularity update), so caches built from
 * them (popular routes) are cleared.
 *
 * @param reason what changed ("cleanup", "popularity")
 */
public record PlacesChangedEvent(String reason) {
}

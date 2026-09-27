package com.nomi.wayfinder.osm;

// Published after a successful OSM import (any trigger); the place photo resolver runs after it
public record OsmImportFinishedEvent(OsmPlaceImporter.ImportResult result) {
}

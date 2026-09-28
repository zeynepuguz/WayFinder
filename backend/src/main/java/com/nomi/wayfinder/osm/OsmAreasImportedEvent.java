package com.nomi.wayfinder.osm;

// Published after districts / neighbourhoods were imported; the assistant's AreaCatalog reloads its list
public record OsmAreasImportedEvent(OsmAreaImporter.AreaImportResult result) {
}

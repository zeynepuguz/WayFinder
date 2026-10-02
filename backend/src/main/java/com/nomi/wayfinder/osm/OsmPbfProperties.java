package com.nomi.wayfinder.osm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenStreetMap from a Geofabrik extract instead of the Overpass API ("nomi.osm-pbf.*" in application.yml).
 *
 * @param enabled     true = every OSM import reads the extract (PbfOsmSource); false = Overpass (OverpassClient)
 * @param path        the Türkiye extract, e.g. C:/Models/osm-data/turkey-260929.osm.pbf
 *                    (download.geofabrik.de/europe/turkey-YYMMDD.osm.pbf)
 * @param cachePath   DuckDB file the extract is prepared into once (rebuilt when the extract is newer)
 * @param memoryLimit DuckDB memory limit ("2GB")
 */
@ConfigurationProperties(prefix = "nomi.osm-pbf")
public record OsmPbfProperties(
        boolean enabled,
        String path,
        String cachePath,
        String memoryLimit
) {
}

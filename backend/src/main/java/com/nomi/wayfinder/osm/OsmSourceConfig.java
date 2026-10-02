package com.nomi.wayfinder.osm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// The OsmSource every importer uses: the local Geofabrik extract when nomi.osm-pbf.enabled, else the Overpass API
@Configuration
public class OsmSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(OsmSourceConfig.class);

    @Bean
    @Primary
    public OsmSource osmSource(OsmPbfProperties pbf, OverpassClient overpass, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        if (pbf != null && pbf.enabled()) {
            log.info("OSM source: Geofabrik extract {} (cache {})", pbf.path(), pbf.cachePath());
            return new PbfOsmSource(pbf, jdbc, jsonMapper);
        }
        log.info("OSM source: Overpass API");
        return overpass;
    }
}

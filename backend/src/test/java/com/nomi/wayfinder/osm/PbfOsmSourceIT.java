package com.nomi.wayfinder.osm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Geofabrik extract answers like Overpass. Runs only where the extract is on disk (development machine):
 * OSM_PBF_TEST_PATH, default C:/Models/osm-data/turkey-260930.osm.pbf. Adana (relation 167216) gave 2166 elements
 * from Overpass on 2026-09-30.
 */
@SpringBootTest(properties = {
        "nomi.osm-pbf.enabled=true",
        "nomi.osm-pbf.path=${OSM_PBF_TEST_PATH:C:/Models/osm-data/turkey-260930.osm.pbf}",
        "nomi.osm-pbf.cache-path=${OSM_PBF_TEST_CACHE:C:/Models/osm-data/test-cache.duckdb}"
})
@EnabledIf("extractPresent")
class PbfOsmSourceIT {

    static final long ADANA = 167216;

    @Autowired
    OsmSource source;

    static boolean extractPresent() {
        String path = System.getenv().getOrDefault("OSM_PBF_TEST_PATH", "C:/Models/osm-data/turkey-260930.osm.pbf");
        return new File(path).isFile();
    }

    @Test
    void answersLikeOverpassForAdana() {
        assertThat(source).isInstanceOf(PbfOsmSource.class);

        List<OverpassResponse.Element> places = source.fetchPlaces(ADANA);
        // Overpass: 2166 (a day apart)
        assertThat(places.size()).isBetween(2100, 2250);
        assertThat(places).allMatch(e -> e.latitude() != null && e.longitude() != null && e.tag("name") != null);
        assertThat(places).anyMatch(e -> "way".equals(e.type()) && e.center() != null && e.lat() == null);

        List<OverpassResponse.Element> districts = source.fetchDistricts(ADANA);
        assertThat(districts).hasSize(15);
        assertThat(districts).allMatch(d -> d.members() != null
                && d.members().stream().anyMatch(m -> "way".equals(m.type()) && m.geometry() != null
                && m.geometry().size() >= 2));

        assertThat(source.fetchAreas(ADANA)).hasSizeGreaterThan(100)
                .allMatch(a -> "node".equals(a.type()) && a.lat() != null && a.tag("place") != null);

        List<OverpassResponse.Element> institutions = source.fetchInstitutions(ADANA);
        assertThat(institutions).isNotEmpty();
        assertThat(institutions).filteredOn(i -> "way".equals(i.type()))
                .allMatch(w -> w.geometry() != null && !w.geometry().isEmpty());

        // Adana is on the Mediterranean; Ankara's box has no coast
        assertThat(source.fetchCoastline(36.5, 34.8, 37.0, 36.0)).isNotEmpty()
                .allMatch(c -> c.geometry() != null && c.geometry().size() >= 2);
        assertThat(source.fetchCoastline(39.8, 32.6, 40.0, 33.0)).isEmpty();

        List<OverpassResponse.Element> provinces = source.fetchProvinces();
        assertThat(provinces).hasSize(81);
        assertThat(provinces).allMatch(p -> p.tag("ISO3166-2").startsWith("TR-") && !p.members().isEmpty());
    }
}

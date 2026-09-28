package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.osm.OverpassResponse.Center;
import com.nomi.wayfinder.osm.OverpassResponse.Member;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// The pure parts of the Turkey-wide import: provinces, the order of cities, the per-city Overpass queries
class OsmCityImportTest {

    private static final List<Center> RING = List.of(new Center(39.0, 32.0), new Center(39.0, 33.0),
            new Center(40.0, 33.0), new Center(39.0, 32.0));

    @Test
    void provinceUsesItsIsoCodePopulationAndAdminCentre() {
        OverpassResponse.Element ankara = new OverpassResponse.Element("relation", 223422, null, null, null,
                Map.of("name", "Ankara", "ISO3166-2", "TR-06", "population", "5803482", "admin_level", "4"),
                List.of(new Member("way", 1, "outer", null, null, RING),
                        new Member("node", 2, "label", 39.70, 32.70, null),
                        new Member("node", 3, "admin_centre", 39.9208, 32.8541, null)));

        OsmCityImporter.ProvinceInput province = OsmCityImporter.toProvince(ankara);

        assertThat(province.boundary().osmId()).isEqualTo("relation/223422");
        assertThat(province.boundary().slug()).isEqualTo("ankara");
        assertThat(province.isoCode()).isEqualTo("TR-06");
        assertThat(province.population()).isEqualTo(5_803_482L);
        // Where a city trip starts: the capital's centre, not the map label position
        assertThat(province.labelLat()).isEqualTo(39.9208);
        assertThat(province.labelLon()).isEqualTo(32.8541);
    }

    @Test
    void neighbouringCountriesAndUnnamedRelationsAreLeftOut() {
        OverpassResponse.Element greek = new OverpassResponse.Element("relation", 4496067, null, null, null,
                Map.of("name", "Αποκεντρωμένη Διοίκηση Αιγαίου", "population", "522763"),
                List.of(new Member("way", 1, "outer", null, null, RING)));
        OverpassResponse.Element noIso = new OverpassResponse.Element("relation", 5, null, null, null,
                Map.of("name", "Somewhere"), List.of(new Member("way", 1, "outer", null, null, RING)));
        OverpassResponse.Element noWays = new OverpassResponse.Element("relation", 6, null, null, null,
                Map.of("name", "Van", "ISO3166-2", "TR-65"), List.of());

        assertThat(OsmCityImporter.toProvince(greek)).isNull();
        assertThat(OsmCityImporter.toProvince(noIso)).isNull();
        assertThat(OsmCityImporter.toProvince(noWays)).isNull();

        OsmCityImporter.ProvinceInput withoutCentre = OsmCityImporter.toProvince(new OverpassResponse.Element(
                "relation", 223442, null, null, null, Map.of("name", "Van", "ISO3166-2", "tr-65"),
                List.of(new Member("way", 1, "outer", null, null, RING))));
        assertThat(withoutCentre.isoCode()).isEqualTo("TR-65");
        assertThat(withoutCentre.population()).isNull();
        // PostGIS picks a point inside the polygon
        assertThat(withoutCentre.labelLat()).isNull();
    }

    @Test
    void populationTagsAreReadCarefully() {
        assertThat(OsmCityImporter.population("15701602")).isEqualTo(15_701_602L);
        assertThat(OsmCityImporter.population("5.803.482")).isEqualTo(5_803_482L);
        assertThat(OsmCityImporter.population("about 2 million")).isNull();
        assertThat(OsmCityImporter.population(null)).isNull();
    }

    @Test
    void relationIdComesFromTheOsmId() {
        assertThat(OsmCityImporter.relationId("relation/223474")).isEqualTo(223474L);
        assertThat(OsmCityImporter.relationId("node/1")).isZero();
        assertThat(OsmCityImporter.relationId(null)).isZero();
    }

    @Test
    void istanbulFirstThenByPopulationThenByName() {
        Instant old = Instant.parse("2026-08-01T00:00:00Z");
        List<OsmCityImporter.CityRow> cities = List.of(
                row("van", null, null),
                row("ankara", 5_803_482L, null),
                row("istanbul", 15_701_602L, old),
                row("izmir", null, null),
                row("adana", 2_201_670L, Instant.parse("2026-09-27T00:00:00Z")),
                row("bolu", 320_014L, null));
        Instant cutoff = Instant.parse("2026-09-07T00:00:00Z");

        assertThat(OsmImportJobs.plan(cities, null, false, false, cutoff)).extracting(OsmCityImporter.CityRow::slug)
                // adana was imported inside the refresh window
                .containsExactly("istanbul", "ankara", "bolu", "izmir", "van");
        assertThat(OsmImportJobs.plan(cities, null, true, false, cutoff)).extracting(OsmCityImporter.CityRow::slug)
                .containsExactly("istanbul", "ankara", "adana", "bolu", "izmir", "van");
        // Startup: only cities never imported
        assertThat(OsmImportJobs.plan(cities, null, false, true, cutoff)).extracting(OsmCityImporter.CityRow::slug)
                .containsExactly("ankara", "bolu", "izmir", "van");
        // nomi.osm.cities=izmir,istanbul
        assertThat(OsmImportJobs.plan(cities, Set.of("izmir", "istanbul"), true, false, cutoff))
                .extracting(OsmCityImporter.CityRow::slug).containsExactly("istanbul", "izmir");
    }

    @Test
    void perCityQueriesUseTheCityAreaAndIstanbulKeepsItsQuery() {
        assertThat(OverpassClient.areaId(223474)).isEqualTo(3600223474L);
        assertThat(OverpassClient.ISTANBUL_QUERY).contains("area(id:3600223474)->.city;")
                .contains("nwr[\"amenity\"~\"^(cafe|restaurant|ice_cream|theatre|arts_centre)$\"][\"name\"](area.city);")
                .contains("nwr[\"shop\"~\"^(pastry|confectionery)$\"][\"name\"](area.city);")
                .contains("nwr[\"tourism\"~\"^(museum|attraction|viewpoint)$\"][\"name\"](area.city);")
                .contains("nwr[\"leisure\"=\"park\"][\"name\"](area.city);")
                .contains("out center tags;");
        assertThat(OverpassClient.placesQuery(223422)).contains("area(id:3600223422)");
        assertThat(OverpassClient.PROVINCES_QUERY).contains("area(id:3600174737)->.tr;")
                .contains("[\"admin_level\"=\"4\"](area.tr);").contains("out geom;");
    }

    @Test
    void citiesSettingIsAllOrASlugList() {
        assertThat(osm("all").selectedCities()).isNull();
        assertThat(osm(" ").selectedCities()).isNull();
        assertThat(osm("Istanbul, ankara ,izmir").selectedCities()).containsExactly("istanbul", "ankara", "izmir");
    }

    private static com.nomi.wayfinder.config.NomiProperties.Osm osm(String cities) {
        return new com.nomi.wayfinder.config.NomiProperties.Osm(false, "-", cities, null, null, List.of(), null, null,
                1, null);
    }

    private static OsmCityImporter.CityRow row(String slug, Long population, Instant importedAt) {
        return new OsmCityImporter.CityRow(slug.hashCode(), slug, slug, 1, population, importedAt);
    }
}

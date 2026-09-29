package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.osm.OverpassResponse.Center;
import com.nomi.wayfinder.osm.OverpassResponse.Member;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Interest tags from OSM facts, and the institution / coastline import's pure parts (no network)
class PlaceTagsAndContextTest {

    @Test
    void cuisineAndNameWordsGiveLocalSeafoodAndBudgetTags() {
        assertThat(PlaceTags.derive("Kıyı Restoran", PlaceCategory.RESTAURANT, List.of("fish"), Map.of(), List.of()))
                .contains("seafood", "local");
        assertThat(PlaceTags.derive("Midyeci Ahmet", PlaceCategory.RESTAURANT, List.of(), Map.of(), List.of()))
                .contains("seafood");
        assertThat(PlaceTags.derive("Karadeniz Pide Salonu", PlaceCategory.RESTAURANT, List.of(), Map.of(), List.of()))
                .contains("local");
        assertThat(PlaceTags.derive("Anne Ev Yemekleri", PlaceCategory.RESTAURANT, List.of(), Map.of(), List.of()))
                .contains("local", "budget");
        assertThat(PlaceTags.derive("Simitçi Dünyası", PlaceCategory.CAFE, List.of(), Map.of(), List.of()))
                .contains("budget");
        assertThat(PlaceTags.derive("Kadıköy Belediyesi Sosyal Tesisleri", PlaceCategory.CAFE, List.of(), Map.of(),
                List.of())).contains("budget");
        assertThat(PlaceTags.derive("Bir Kafe", PlaceCategory.CAFE, List.of(), Map.of(), List.of("quick")))
                .contains("budget");
        // "Balıkesir" is a city, not fish
        assertThat(PlaceTags.derive("Balıkesir Mantı Evi", PlaceCategory.RESTAURANT, List.of(), Map.of(), List.of()))
                .doesNotContain("seafood").contains("local");
        // Nothing stated: nothing derived
        assertThat(PlaceTags.derive("Kahve Durağı", PlaceCategory.CAFE, List.of("coffee_shop"), Map.of(), List.of()))
                .isEmpty();
    }

    @Test
    void booksViewsSeaArtAndArchitectureComeFromOsmTagsOrNames() {
        assertThat(PlaceTags.derive("Kitap Kafe", PlaceCategory.CAFE, List.of(), Map.of(), List.of()))
                .contains("books");
        assertThat(PlaceTags.derive("Teras Cafe", PlaceCategory.CAFE, List.of(), Map.of(), List.of()))
                .contains("view");
        assertThat(PlaceTags.derive("Moda Sahil Parkı", PlaceCategory.PARK, List.of(), Map.of(), List.of()))
                .contains("sea", "nature");
        assertThat(PlaceTags.derive("Duvar Resmi", PlaceCategory.ATTRACTION, List.of(),
                Map.of("tourism", "artwork", "artwork_type", "mural"), List.of())).contains("art", "street-art");
        assertThat(PlaceTags.derive("Rumeli Hisarı", PlaceCategory.ATTRACTION, List.of(),
                Map.of("historic", "castle"), List.of())).contains("architecture");
        // A pudding shop named "Saray" is not architecture
        assertThat(PlaceTags.derive("Saray Muhallebicisi", PlaceCategory.DESSERT, List.of(), Map.of(), List.of()))
                .doesNotContain("architecture");
        assertThat(PlaceTags.derive("Sanat Evi", PlaceCategory.CULTURE, List.of(), Map.of(), List.of()))
                .contains("art");
    }

    @Test
    void streetArtIsImportedAsASight() {
        OsmPlaceMapper.OsmPlace mural = OsmPlaceMapper.map(new OverpassResponse.Element("node", 1, 41.0, 29.0, null,
                Map.of("tourism", "artwork", "artwork_type", "mural", "name", "Yeldeğirmeni Duvar Resmi")));
        assertThat(mural.category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(mural.tags()).contains("art", "street-art");
        assertThat(OsmPlaceMapper.map(new OverpassResponse.Element("node", 2, 41.0, 29.0, null,
                Map.of("tourism", "artwork", "artwork_type", "statue", "name", "Bir Heykel")))).isNull();
        assertThat(OverpassClient.placesQueries(223474L).get(1)).contains("\"tourism\"=\"artwork\"");
    }

    // ---------- institutions / coastline ----------

    private static final List<Center> CLOSED = List.of(new Center(40.80, 29.35), new Center(40.80, 29.36),
            new Center(40.81, 29.36), new Center(40.81, 29.35), new Center(40.80, 29.35));

    @Test
    void closedWaysAndMultipolygonsOfInstitutionsBecomeAreas() {
        OverpassResponse.Element campus = new OverpassResponse.Element("way", 10, null, null, null,
                Map.of("amenity", "university", "name", "Gebze Teknik Üniversitesi"), null, CLOSED);
        OsmContextImporter.InstitutionInput input = OsmContextImporter.toInstitution(campus);
        assertThat(input.osmId()).isEqualTo("way/10");
        assertThat(input.kind()).isEqualTo("university");
        assertThat(input.name()).isEqualTo("Gebze Teknik Üniversitesi");
        assertThat(input.wayWkts()).containsExactly(
                "LINESTRING(29.3500000 40.8000000,29.3600000 40.8000000,29.3600000 40.8100000,29.3500000 40.8100000,"
                        + "29.3500000 40.8000000)");

        OverpassResponse.Element industrial = new OverpassResponse.Element("relation", 11, null, null, null,
                Map.of("landuse", "industrial", "type", "multipolygon"),
                List.of(new Member("way", 1, "outer", null, null, CLOSED),
                        new Member("way", 2, "inner", null, null, CLOSED.subList(0, 3)),
                        new Member("node", 3, "label", 40.805, 29.355, null)));
        OsmContextImporter.InstitutionInput zone = OsmContextImporter.toInstitution(industrial);
        assertThat(zone.kind()).isEqualTo("landuse:industrial");
        assertThat(zone.wayWkts()).hasSize(2);

        assertThat(OsmContextImporter.toInstitution(new OverpassResponse.Element("way", 12, null, null, null,
                Map.of("military", "barracks"), null, CLOSED)).kind()).isEqualTo("military:barracks");
    }

    @Test
    void openWaysAndOtherLandUsesAreNotInstitutionAreas() {
        // An unclosed "school" way is a fence or a mapping error, not an area
        assertThat(OsmContextImporter.toInstitution(new OverpassResponse.Element("way", 1, null, null, null,
                Map.of("amenity", "school"), null, CLOSED.subList(0, 4)))).isNull();
        assertThat(OsmContextImporter.toInstitution(new OverpassResponse.Element("way", 2, null, null, null,
                Map.of("landuse", "residential"), null, CLOSED))).isNull();
        assertThat(OsmContextImporter.toInstitution(new OverpassResponse.Element("way", 3, null, null, null,
                Map.of("amenity", "hospital"), null, null))).isNull();
    }

    @Test
    void coastlineWaysAreLines() {
        assertThat(OsmContextImporter.lineWkt(List.of(new Center(40.98, 29.02), new Center(40.99, 29.03))))
                .isEqualTo("LINESTRING(29.0200000 40.9800000,29.0300000 40.9900000)");
        assertThat(OsmContextImporter.lineWkt(List.of(new Center(40.98, 29.02)))).isNull();
    }

    @Test
    void contextQueriesAskForAreasAndTheCoast() {
        assertThat(OverpassClient.INSTITUTIONS_QUERY)
                .contains("amenity\"~\"^(university|college|school|hospital|prison)$\"")
                .contains("landuse\"~\"^(military|industrial)$\"")
                .contains("way[\"military\"]")
                .contains("out geom;")
                // areas only: no nodes
                .doesNotContain("node[");
        assertThat(OverpassClient.COASTLINE_QUERY.formatted(OverpassClient.bbox(40.8, 28.0, 41.6, 29.9)))
                .contains("way[\"natural\"=\"coastline\"](40.80000,28.00000,41.60000,29.90000)")
                .contains("out geom;");
        // Museums / sights / culture venues, Wikidata items and verified places stay plannable on a campus
        assertThat(OsmContextImporter.FLAG_INSIDE_INSTITUTION)
                .contains("p2.source IN ('OSM', 'OVERTURE')", "p2.wikidata IS NULL", "NOT IN ('MUSEUM', 'ATTRACTION', 'CULTURE')");
    }
}

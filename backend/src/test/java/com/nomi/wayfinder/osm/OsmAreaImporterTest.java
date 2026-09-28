package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.osm.OverpassResponse.Center;
import com.nomi.wayfinder.osm.OverpassResponse.Member;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// The pure part of the district import: Overpass relation -> ways as WKT + label point (PostGIS builds the polygon)
class OsmAreaImporterTest {

    @Test
    void relationBecomesWaysWktSlugAndLabelPoint() {
        OverpassResponse.Element relation = new OverpassResponse.Element("relation", 1276889, null, null, null,
                Map.of("name", "Üsküdar", "admin_level", "6"),
                List.of(
                        new Member("way", 1, "outer", null, null,
                                List.of(new Center(41.0, 29.0), new Center(41.0, 29.1), new Center(41.1, 29.1))),
                        new Member("way", 2, "outer", null, null,
                                List.of(new Center(41.1, 29.1), new Center(41.0, 29.0))),
                        new Member("node", 3, "admin_centre", 41.0227, 29.0150, null),
                        new Member("relation", 4, "subarea", null, null, null)));

        OsmAreaImporter.DistrictInput district = OsmAreaImporter.toDistrict(relation);

        assertThat(district.osmId()).isEqualTo("relation/1276889");
        assertThat(district.slug()).isEqualTo("uskudar");
        assertThat(district.wayWkts()).containsExactly(
                "LINESTRING(29.0000000 41.0000000,29.1000000 41.0000000,29.1000000 41.1000000)",
                "LINESTRING(29.1000000 41.1000000,29.0000000 41.0000000)");
        assertThat(district.labelLat()).isEqualTo(41.0227);
        assertThat(district.labelLon()).isEqualTo(29.0150);
    }

    @Test
    void labelNodeWinsOverAdminCentreAndNoNodeLeavesItToPostgis() {
        List<Center> ring = List.of(new Center(41.0, 29.0), new Center(41.0, 29.1), new Center(41.0, 29.0));
        OverpassResponse.Element both = new OverpassResponse.Element("relation", 1, null, null, null,
                Map.of("name", "Kadıköy"), List.of(new Member("way", 1, "outer", null, null, ring),
                new Member("node", 2, "admin_centre", 40.99, 29.02, null),
                new Member("node", 3, "label", 40.98, 29.03, null)));
        OverpassResponse.Element none = new OverpassResponse.Element("relation", 2, null, null, null,
                Map.of("name", "Büyükçekmece"), List.of(new Member("way", 1, "outer", null, null, ring)));

        assertThat(OsmAreaImporter.toDistrict(both).labelLat()).isEqualTo(40.98);
        OsmAreaImporter.DistrictInput withoutLabel = OsmAreaImporter.toDistrict(none);
        assertThat(withoutLabel.labelLat()).isNull();
        assertThat(withoutLabel.slug()).isEqualTo("buyukcekmece");
    }

    @Test
    void relationWithoutNameOrWaysIsSkipped() {
        assertThat(OsmAreaImporter.toDistrict(new OverpassResponse.Element("relation", 1, null, null, null,
                Map.of("admin_level", "6"), List.of()))).isNull();
        assertThat(OsmAreaImporter.toDistrict(new OverpassResponse.Element("relation", 1, null, null, null,
                Map.of("name", "Şile"), List.of(new Member("node", 2, "admin_centre", 41.1, 29.6, null))))).isNull();
    }

    @Test
    void neighbourhoodNodesBecomeAreas() {
        OsmAreaImporter.AreaInput moda = OsmAreaImporter.toArea(new OverpassResponse.Element("node", 7, 40.98, 29.03,
                null, Map.of("place", "quarter", "name", "Moda")));

        assertThat(moda).isEqualTo(new OsmAreaImporter.AreaInput("node/7", "Moda", "moda", "quarter", 40.98, 29.03));
        assertThat(OsmAreaImporter.toArea(new OverpassResponse.Element("node", 8, 40.98, 29.03, null,
                Map.of("place", "city", "name", "İstanbul")))).isNull();
    }

    @Test
    void overpassOutGeomJsonIsRead() {
        String json = """
                {"elements":[{"type":"relation","id":1276889,"bounds":{"minlat":41.0},
                "members":[{"type":"way","ref":1,"role":"outer","geometry":[{"lat":41.0,"lon":29.0},{"lat":41.1,"lon":29.1}]},
                {"type":"node","ref":2,"role":"admin_centre","lat":41.02,"lon":29.01}],
                "tags":{"name":"Üsküdar","admin_level":"6"}}]}
                """;

        List<OverpassResponse.Element> elements = OverpassClient.parse(json, JsonMapper.builder().build());

        assertThat(elements.getFirst().members()).hasSize(2);
        assertThat(elements.getFirst().members().getFirst().geometry()).hasSize(2);
        assertThat(OsmAreaImporter.toDistrict(elements.getFirst()).labelLat()).isEqualTo(41.02);
    }
}

package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.osm.OsmDeduplicator.ExistingPlace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OsmDeduplicatorTest {

    // A verified place in Kadıköy
    private final OsmDeduplicator deduplicator = new OsmDeduplicator(List.of(
            new ExistingPlace("Çiya Sofrası", 40.98970, 29.02660)));

    @Test
    void sameNameCloseByIsADuplicate() {
        // ~20 m away, different spelling / case
        assertThat(deduplicator.isDuplicate(osm("node/1", "ÇİYA SOFRASI", 40.98985, 29.02670))).isTrue();
        // One name contains the other
        assertThat(deduplicator.isDuplicate(osm("node/2", "Çiya", 40.98975, 29.02650))).isTrue();
    }

    @Test
    void differentNameOrFarAwayIsKept() {
        assertThat(deduplicator.isDuplicate(osm("node/3", "Çiya Kebap 2", 40.98975, 29.02650))).isFalse();
        // Same name ~200 m away: another branch
        assertThat(deduplicator.isDuplicate(osm("node/4", "Çiya Sofrası", 40.99150, 29.02660))).isFalse();
    }

    @Test
    void prepareCountsUnusableAndDuplicateElementsAndMergesRepeatedIds() {
        List<OverpassResponse.Element> elements = List.of(
                element("node", 1, "Çiya Sofrası", 40.98980, 29.02665),
                element("node", 2, "Yeni Kafe", 40.99, 29.03),
                element("node", 2, "Yeni Kafe", 40.99, 29.03),
                element("node", 3, null, 40.99, 29.03));

        OsmPlaceImporter.Prepared prepared = OsmPlaceImporter.prepare(elements, deduplicator);

        assertThat(prepared.places()).extracting(OsmPlaceMapper.OsmPlace::osmId).containsExactly("node/2");
        assertThat(prepared.skippedDuplicates()).isEqualTo(1);
        assertThat(prepared.skippedUnusable()).isEqualTo(1);
    }

    private static OsmPlaceMapper.OsmPlace osm(String id, String name, double lat, double lon) {
        return OsmPlaceMapper.map(element(id.split("/")[0], Long.parseLong(id.split("/")[1]), name, lat, lon));
    }

    private static OverpassResponse.Element element(String type, long id, String name, double lat, double lon) {
        Map<String, String> tags = name == null ? Map.of("amenity", "cafe") : Map.of("amenity", "cafe", "name", name);
        return new OverpassResponse.Element(type, id, lat, lon, null, tags);
    }
}

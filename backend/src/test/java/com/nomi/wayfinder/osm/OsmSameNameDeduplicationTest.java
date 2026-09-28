package com.nomi.wayfinder.osm;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// OSM often maps one place twice: only one of them may become a place
class OsmSameNameDeduplicationTest {

    @Test
    void theSameParkTwice300MetresApartBecomesOnePlace() {
        // "Şehit İbrahim Doğan Parkı" is in OSM as a node and as a way, ~300 m apart
        List<OsmPlaceMapper.OsmPlace> places = List.of(
                park("node/500", "Şehit İbrahim Doğan Parkı", 41.0000, 29.0000, Map.of()),
                park("way/900", "ŞEHİT İBRAHİM DOĞAN PARKI", 41.0027, 29.0000, Map.of()));

        OsmDeduplicator.Deduped deduped = OsmDeduplicator.dedupeAmongThemselves(places);

        // The way (the real outline) wins over the node
        assertThat(deduped.kept()).extracting(OsmPlaceMapper.OsmPlace::osmId).containsExactly("way/900");
        assertThat(deduped.droppedOsmIds()).containsExactly("node/500");
    }

    @Test
    void theOneWithAPhotoReferenceOrHoursIsKept() {
        List<OsmPlaceMapper.OsmPlace> places = List.of(
                park("way/1", "Fethi Paşa Korusu", 41.0000, 29.0000, Map.of()),
                park("node/2", "Fethi Paşa Korusu", 41.0010, 29.0000, Map.of("wikidata", "Q123")));

        assertThat(OsmDeduplicator.dedupeAmongThemselves(places).kept())
                .extracting(OsmPlaceMapper.OsmPlace::osmId).containsExactly("node/2");
    }

    @Test
    void lowerIdWinsWhenNothingElseDecides() {
        List<OsmPlaceMapper.OsmPlace> places = List.of(
                cafe("node/20", "Kahve Durağı", 41.0000, 29.0000),
                cafe("node/10", "Kahve Durağı", 41.0005, 29.0000));

        assertThat(OsmDeduplicator.dedupeAmongThemselves(places).droppedOsmIds()).containsExactly("node/20");
    }

    @Test
    void cafesFurtherThan150MetresApartAreBranchesButParksWithin600MetresAreNot() {
        // ~220 m apart
        List<OsmPlaceMapper.OsmPlace> cafes = List.of(
                cafe("node/1", "Kahve Durağı", 41.0000, 29.0000),
                cafe("node/2", "Kahve Durağı", 41.0020, 29.0000));
        List<OsmPlaceMapper.OsmPlace> parks = List.of(
                park("node/3", "Moda Parkı", 41.0000, 29.0000, Map.of()),
                park("node/4", "Moda Parkı", 41.0020, 29.0000, Map.of()));
        // ~890 m apart
        List<OsmPlaceMapper.OsmPlace> farParks = List.of(
                park("node/5", "Millet Bahçesi", 41.0000, 29.0000, Map.of()),
                park("node/6", "Millet Bahçesi", 41.0080, 29.0000, Map.of()));

        assertThat(OsmDeduplicator.dedupeAmongThemselves(cafes).kept()).hasSize(2);
        assertThat(OsmDeduplicator.dedupeAmongThemselves(parks).kept()).hasSize(1);
        assertThat(OsmDeduplicator.dedupeAmongThemselves(farParks).kept()).hasSize(2);
    }

    @Test
    void differentCategoryOrNameIsNotADuplicate() {
        List<OsmPlaceMapper.OsmPlace> places = List.of(
                cafe("node/1", "Moda", 41.0000, 29.0000),
                park("node/2", "Moda", 41.0001, 29.0000, Map.of()),
                cafe("node/3", "Moda Kahvesi", 41.0001, 29.0001));

        assertThat(OsmDeduplicator.dedupeAmongThemselves(places).kept()).hasSize(3);
    }

    @Test
    void prepareDropsTheSecondCopyAndReportsItForCleanup() {
        List<OverpassResponse.Element> elements = List.of(
                new OverpassResponse.Element("node", 500, 41.0000, 29.0000, null,
                        Map.of("leisure", "park", "name", "Şehit İbrahim Doğan Parkı")),
                new OverpassResponse.Element("way", 900, null, null, new OverpassResponse.Center(41.0027, 29.0),
                        Map.of("leisure", "park", "name", "Şehit İbrahim Doğan Parkı")));

        OsmPlaceImporter.Prepared prepared = OsmPlaceImporter.prepare(elements, new OsmDeduplicator(List.of()));

        assertThat(prepared.places()).extracting(OsmPlaceMapper.OsmPlace::osmId).containsExactly("way/900");
        assertThat(prepared.osmDuplicateIds()).containsExactly("node/500");
    }

    private static OsmPlaceMapper.OsmPlace park(String id, String name, double lat, double lon, Map<String, String> extra) {
        Map<String, String> tags = new HashMap<>(extra);
        tags.put("leisure", "park");
        tags.put("name", name);
        return map(id, lat, lon, tags);
    }

    private static OsmPlaceMapper.OsmPlace cafe(String id, String name, double lat, double lon) {
        return map(id, lat, lon, Map.of("amenity", "cafe", "name", name));
    }

    private static OsmPlaceMapper.OsmPlace map(String id, double lat, double lon, Map<String, String> tags) {
        String[] parts = id.split("/");
        return OsmPlaceMapper.map(new OverpassResponse.Element(parts[0], Long.parseLong(parts[1]), lat, lon, null, tags));
    }
}

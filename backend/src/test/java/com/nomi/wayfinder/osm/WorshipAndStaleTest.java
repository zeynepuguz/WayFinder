package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorshipAndStaleTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void cemevleriAndSynagoguesMappedWithoutPlaceOfWorship() {
        OsmPlaceMapper.OsmPlace cemevi = OsmPlaceMapper.map(element(null, "name", "Şahkulu Sultan Dergahı",
                "denomination", "alevi", "religion", "muslim", "building", "yes"));
        assertThat(cemevi.category()).isEqualTo(PlaceCategory.WORSHIP);
        assertThat(cemevi.tags()).contains("religious", "cemevi");

        OsmPlaceMapper.OsmPlace synagogue = OsmPlaceMapper.map(element(null, "name", "Neve Şalom",
                "religion", "jewish", "building", "yes"));
        assertThat(synagogue.tags()).contains("synagogue");

        // place_of_worship with religion=muslim but denomination=alevi is a cemevi, not a mosque
        OsmPlaceMapper.OsmPlace alevi = OsmPlaceMapper.map(element(null, "amenity", "place_of_worship",
                "religion", "muslim", "denomination", "alevi", "name", "Hacı Bektaş Veli Kültür Merkezi"));
        assertThat(alevi.tags()).contains("cemevi").doesNotContain("mosque");

        assertThat(PlaceTags.worshipKind("Gebze Cemevi", "muslim", null)).isEqualTo("cemevi");
    }

    @Test
    void namesAreMatchedAsWholeWordsAndNamesakesAreLeftOut() {
        // Upper case with İ and "Cem Evi" in two words, both without place_of_worship
        assertThat(OsmPlaceMapper.map(element(null, "name", "ACIPINAR CEMEVİ", "building", "yes")).tags())
                .contains("cemevi");
        assertThat(OsmPlaceMapper.map(element(null, "name", "Burhaniye Cem Evi", "building", "yes")).tags())
                .contains("cemevi");
        assertThat(OsmPlaceMapper.map(element(null, "name", "Eski Havra", "building", "yes")).tags())
                .contains("synagogue");

        // "Havra" inside other words, streets, cemeteries and tombs named after one are not places of worship
        assertThat(OsmPlaceMapper.map(element(null, "name", "Havraniye Mah."))).isNull();
        assertThat(OsmPlaceMapper.map(element(null, "name", "Havran", "place", "town"))).isNull();
        assertThat(OsmPlaceMapper.map(element(null, "name", "Cemevi Sokağı", "highway", "residential"))).isNull();
        assertThat(OsmPlaceMapper.map(element(null, "name", "Arnavutköy Musevi Mezarlığı", "religion", "jewish",
                "landuse", "cemetery"))).isNull();
        assertThat(OsmPlaceMapper.map(element(null, "name", "Acıpınar Köyü Mezarlığı", "religion", "muslim",
                "denomination", "alevi"))).isNull();
        assertThat(PlaceTags.worshipKind("Havraniye Camii", "muslim", null)).isEqualTo("mosque");
    }

    @Test
    void staleIsOldAndWithoutContactData() {
        // Neşetbey Et Lokantası: version 1 from 2018, name and amenity only
        assertThat(OsmPlaceMapper.stale(element("2018-07-02T18:11:21Z", "amenity", "restaurant", "name", "Neşetbey"),
                NOW)).isTrue();
        // Old but with a phone: someone keeps it up to date
        assertThat(OsmPlaceMapper.stale(element("2018-07-02T18:11:21Z", "amenity", "restaurant", "name", "X",
                "phone", "+90 262 000 00 00"), NOW)).isFalse();
        // Edited last year
        assertThat(OsmPlaceMapper.stale(element("2025-11-02T10:00:00Z", "amenity", "restaurant", "name", "Y"), NOW))
                .isFalse();
        // No edit time known
        assertThat(OsmPlaceMapper.stale(element(null, "amenity", "restaurant", "name", "Z"), NOW)).isFalse();
    }

    private static OverpassResponse.Element element(String timestamp, String... keyValues) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            tags.put(keyValues[i], keyValues[i + 1]);
        }
        return new OverpassResponse.Element("node", 1, 40.8, 29.37, null, tags, null, null, timestamp);
    }
}

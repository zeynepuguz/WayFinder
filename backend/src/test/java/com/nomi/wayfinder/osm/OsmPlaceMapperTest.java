package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.osm.OsmPlaceMapper.OsmPlace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A small, hand-written Overpass answer ("out center tags"); no network
class OsmPlaceMapperTest {

    private static final String SAMPLE = """
            {
              "version": 0.6,
              "elements": [
                {"type": "node", "id": 1, "lat": 40.99, "lon": 29.02,
                 "tags": {"amenity": "cafe", "name": "Kahve Durağı", "cuisine": "coffee_shop",
                          "opening_hours": "Mo-Su 08:00-22:00", "addr:street": "Moda Caddesi",
                          "addr:housenumber": "12", "addr:suburb": "Caferağa"}},
                {"type": "node", "id": 2, "lat": 40.98, "lon": 29.03,
                 "tags": {"amenity": "restaurant", "name": "Van KAHVALTI Evi", "addr:district": "Kadıköy"}},
                {"type": "node", "id": 3, "lat": 40.97, "lon": 29.04,
                 "tags": {"amenity": "restaurant", "name": "Balıkçı", "cuisine": "fish;turkish"}},
                {"type": "way", "id": 4, "center": {"lat": 41.01, "lon": 28.98},
                 "tags": {"tourism": "museum", "name": "Eski Müze", "historic": "building",
                          "opening_hours": "Tu-Su 09:00-17:00; PH off"}},
                {"type": "relation", "id": 5, "center": {"lat": 41.02, "lon": 29.0},
                 "tags": {"leisure": "park", "name": "Büyük Park"}},
                {"type": "node", "id": 6, "lat": 41.0, "lon": 29.05,
                 "tags": {"tourism": "viewpoint", "name": "Tepe Seyir"}},
                {"type": "node", "id": 7, "lat": 41.0, "lon": 29.05,
                 "tags": {"shop": "pastry", "name": "Pastane"}},
                {"type": "node", "id": 8, "lat": 41.0, "lon": 29.05,
                 "tags": {"amenity": "arts_centre", "name": "Sanat Evi"}},
                {"type": "node", "id": 9, "lat": 41.0, "lon": 29.05,
                 "tags": {"amenity": "cafe", "cuisine": "breakfast"}},
                {"type": "node", "id": 10, "lat": 41.0, "lon": 29.05,
                 "tags": {"amenity": "bank", "name": "Banka"}},
                {"type": "way", "id": 11,
                 "tags": {"amenity": "cafe", "name": "Koordinatsız"}},
                {"type": "node", "id": 12, "lat": 41.0, "lon": 29.05,
                 "tags": {"amenity": "cafe", "name": "Sabah", "cuisine": "breakfast;coffee_shop"}}
              ]
            }
            """;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void mapsCategoriesTagsAddressesAndHoursFromOsmFactsOnly() {
        List<OverpassResponse.Element> elements = OverpassClient.parse(SAMPLE, jsonMapper);
        Map<String, OsmPlace> places = elements.stream()
                .map(OsmPlaceMapper::map)
                .filter(p -> p != null)
                .collect(Collectors.toMap(OsmPlace::osmId, Function.identity()));

        // no name (9), unknown kind (10), no coordinates (11) are skipped
        assertThat(places).hasSize(9).doesNotContainKeys("node/9", "node/10", "way/11");

        OsmPlace cafe = places.get("node/1");
        assertThat(cafe.category()).isEqualTo(PlaceCategory.CAFE);
        assertThat(cafe.indoor()).isTrue();
        assertThat(cafe.tags()).containsExactly("coffee");
        assertThat(cafe.address()).isEqualTo("Moda Caddesi 12");
        assertThat(cafe.neighborhood()).isEqualTo("Caferağa");
        assertThat(cafe.sourceUrl()).isEqualTo("https://www.openstreetmap.org/node/1");
        assertThat(cafe.openingHours()).hasSize(7);

        // "KAHVALTI" in the name (uppercase dotless I) makes it a breakfast place
        OsmPlace breakfast = places.get("node/2");
        assertThat(breakfast.category()).isEqualTo(PlaceCategory.BREAKFAST);
        assertThat(breakfast.tags()).containsExactly("breakfast");
        assertThat(breakfast.address()).isNull();
        assertThat(breakfast.neighborhood()).isEqualTo("Kadıköy");
        assertThat(breakfast.openingHours()).isEmpty();

        assertThat(places.get("node/3").category()).isEqualTo(PlaceCategory.RESTAURANT);
        assertThat(places.get("node/3").tags()).containsExactly("seafood");

        OsmPlace museum = places.get("way/4");
        assertThat(museum.category()).isEqualTo(PlaceCategory.MUSEUM);
        assertThat(museum.latitude()).isEqualTo(41.01);
        assertThat(museum.tags()).containsExactlyInAnyOrder("history", "museum");
        // "PH off" is not understood: hours stay unknown instead of guessed
        assertThat(museum.openingHours()).isEmpty();

        OsmPlace park = places.get("relation/5");
        assertThat(park.category()).isEqualTo(PlaceCategory.PARK);
        assertThat(park.indoor()).isFalse();
        assertThat(park.tags()).containsExactly("nature", "walk");

        assertThat(places.get("node/6").category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(places.get("node/6").indoor()).isFalse();
        assertThat(places.get("node/6").tags()).containsExactly("view");

        assertThat(places.get("node/7").category()).isEqualTo(PlaceCategory.DESSERT);
        assertThat(places.get("node/7").tags()).containsExactly("dessert");

        assertThat(places.get("node/8").category()).isEqualTo(PlaceCategory.CULTURE);
        assertThat(places.get("node/8").tags()).containsExactly("art");

        assertThat(places.get("node/12").category()).isEqualTo(PlaceCategory.BREAKFAST);
        assertThat(places.get("node/12").tags()).containsExactlyInAnyOrder("coffee", "breakfast");
    }

    @Test
    void busyServerPagesAndRuntimeErrorsAreRejected() {
        assertThatThrownBy(() -> OverpassClient.parse("<?xml version=\"1.0\"?><osm><remark>busy</remark></osm>", jsonMapper))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OverpassClient.parse("<html><body>Too many requests</body></html>", jsonMapper))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OverpassClient.parse(
                "{\"elements\": [], \"remark\": \"runtime error: Query timed out\"}", jsonMapper))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void foldIgnoresCaseTurkishLettersAndPunctuation() {
        assertThat(OsmPlaceMapper.fold("Çiya Sofrası")).isEqualTo("ciyasofrasi");
        assertThat(OsmPlaceMapper.fold("KAHVALTI")).isEqualTo("kahvalti");
        assertThat(OsmPlaceMapper.fold("İstanbul Modern")).isEqualTo("istanbulmodern");
        assertThat(OsmPlaceMapper.fold("Walter's Coffee")).isEqualTo(OsmPlaceMapper.fold("Walters coffee"));
    }
}

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
    void mapsFastFoodBakeriesSightsAndNatureIntoTheExistingCategories() {
        // Fast food with a name: a real (quick) meal
        OsmPlace doner = map("amenity", "fast_food", "name", "Bereket Döner", "cuisine", "kebab");
        assertThat(doner.category()).isEqualTo(PlaceCategory.RESTAURANT);
        assertThat(doner.tags()).containsExactly("quick");
        assertThat(map("amenity", "fast_food", "name", "Dondurmacı Ali", "cuisine", "ice_cream").category())
                .isEqualTo(PlaceCategory.DESSERT);
        assertThat(map("amenity", "food_court", "name", "Kanyon Yemek Katı").category()).isEqualTo(PlaceCategory.RESTAURANT);
        // Tea gardens are cafes tagged "tea"
        assertThat(map("amenity", "cafe", "name", "Emirgan Çay Bahçesi").tags()).containsExactly("tea");
        assertThat(map("amenity", "cafe", "name", "Ada Çay", "cuisine", "tea").tags()).containsExactly("tea");
        assertThat(map("shop", "coffee", "name", "Kurukahveci Mehmet Efendi").category()).isEqualTo(PlaceCategory.CAFE);

        // Bakeries: pastry shops are desserts, börek / simit shops cafes, bread ovens are not places to go
        assertThat(map("shop", "bakery", "name", "Divan Pastanesi").category()).isEqualTo(PlaceCategory.DESSERT);
        OsmPlace borek = map("shop", "bakery", "name", "Meşhur Sarıyer Börekçisi");
        assertThat(borek.category()).isEqualTo(PlaceCategory.CAFE);
        assertThat(borek.tags()).containsExactly("bakery");
        assertThat(map("shop", "bakery", "name", "Yıldız Ekmek Fırını")).isNull();

        // Places of worship only when notable (Wikidata) or historic
        OsmPlace mosque = map("amenity", "place_of_worship", "religion", "muslim", "name", "Süleymaniye Camii",
                "wikidata", "Q193617");
        assertThat(mosque.category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(mosque.indoor()).isTrue();
        assertThat(mosque.tags()).containsExactlyInAnyOrder("history", "religious");
        assertThat(map("amenity", "place_of_worship", "name", "Tarihi Kilise", "heritage", "2").category())
                .isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(map("amenity", "place_of_worship", "religion", "muslim", "name", "Yeni Mahalle Camii")).isNull();

        // Historic sites; memorials / tombs only with Wikidata
        assertThat(map("historic", "castle", "name", "Rumeli Hisarı").category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(map("historic", "archaeological_site", "name", "Yenikapı Kazısı").tags()).containsExactly("history");
        assertThat(map("historic", "memorial", "name", "Şehitler Anıtı Plaketi")).isNull();
        assertThat(map("historic", "tomb", "name", "Barbaros Hayrettin Paşa Türbesi", "wikidata", "Q6519390").category())
                .isEqualTo(PlaceCategory.ATTRACTION);

        assertThat(map("tourism", "gallery", "name", "Galata Rum Okulu Sanat").category()).isEqualTo(PlaceCategory.CULTURE);
        assertThat(map("tourism", "zoo", "name", "Darıca Hayvanat Bahçesi").category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(map("tourism", "aquarium", "name", "İstanbul Akvaryum").indoor()).isTrue();
        assertThat(map("leisure", "garden", "name", "Gülhane Gül Bahçesi").category()).isEqualTo(PlaceCategory.PARK);
        assertThat(map("leisure", "garden", "name", "Villa Bahçesi", "garden:type", "residential")).isNull();
        assertThat(map("leisure", "nature_reserve", "name", "Belgrad Ormanı").category()).isEqualTo(PlaceCategory.PARK);
        OsmPlace beach = map("natural", "beach", "name", "Kilyos Plajı");
        assertThat(beach.category()).isEqualTo(PlaceCategory.PARK);
        assertThat(beach.tags()).contains("sea", "nature");
        assertThat(map("man_made", "lighthouse", "name", "Ahırkapı Feneri", "wikidata", "Q4696188").tags())
                .containsExactlyInAnyOrder("sea", "view");
        assertThat(map("man_made", "lighthouse", "name", "Mendirek Feneri")).isNull();
        OsmPlace bazaar = map("amenity", "marketplace", "name", "Kadıköy Salı Pazarı");
        assertThat(bazaar.category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(bazaar.tags()).contains("shopping");
    }

    @Test
    void impossibleCoordinatesAreSkipped() {
        assertThat(OsmPlaceMapper.map(new OverpassResponse.Element("node", 30, 0.0, 0.0, null,
                Map.of("amenity", "cafe", "name", "Sıfır Kafe")))).isNull();
        // Swapped lat / lon
        assertThat(OsmPlaceMapper.map(new OverpassResponse.Element("node", 31, 29.02, 40.99, null,
                Map.of("amenity", "cafe", "name", "Ters Kafe")))).isNull();
        // Ways and relations use their center
        assertThat(OsmPlaceMapper.map(new OverpassResponse.Element("way", 32, null, null,
                new OverpassResponse.Center(41.0115, 28.9833), Map.of("tourism", "museum", "name", "Topkapı Sarayı")))
                .latitude()).isEqualTo(41.0115);
    }

    private static OsmPlace map(String... keyValues) {
        Map<String, String> tags = new java.util.HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            tags.put(keyValues[i], keyValues[i + 1]);
        }
        return OsmPlaceMapper.map(new OverpassResponse.Element("node", 40, 41.0, 29.0, null, tags));
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
    void storesWikidataAndCommonsFileForPhotos() {
        OsmPlace museum = OsmPlaceMapper.map(new OverpassResponse.Element("way", 20, null, null,
                new OverpassResponse.Center(41.0086, 28.9802),
                Map.of("tourism", "museum", "name", "Ayasofya", "wikidata", " Q12506 ",
                        "wikimedia_commons", "File:Hagia_Sophia Mars_2013.jpg",
                        "image", "https://upload.wikimedia.org/wikipedia/commons/a/ab/Other.jpg")));
        assertThat(museum.wikidata()).isEqualTo("Q12506");
        // wikimedia_commons wins over image; underscores are spaces in wiki titles
        assertThat(museum.commonsFile()).isEqualTo("Hagia Sophia Mars 2013.jpg");
        assertThat(museum.hasMedia()).isTrue();

        OsmPlace cafe = OsmPlaceMapper.map(new OverpassResponse.Element("node", 21, 40.99, 29.02, null,
                Map.of("amenity", "cafe", "name", "Kafe", "wikidata", "Q1;Q2",
                        "image", "https://example.com/photo.jpg")));
        assertThat(cafe.wikidata()).isNull();
        assertThat(cafe.commonsFile()).isNull();
        assertThat(cafe.hasMedia()).isFalse();
    }

    @Test
    void commonsFileComesOnlyFromCommonsReferences() {
        assertThat(OsmPlaceMapper.commonsFile("File:Galata Kulesi.jpg", null)).isEqualTo("Galata Kulesi.jpg");
        assertThat(OsmPlaceMapper.commonsFile("file:galata.jpg", null)).isEqualTo("Galata.jpg");
        // A category is many photos, not one: fall back to image
        assertThat(OsmPlaceMapper.commonsFile("Category:Galata Tower",
                "https://commons.wikimedia.org/wiki/File:Galata_Tower_%C3%87ok_G%C3%BCzel+1.jpg"))
                .isEqualTo("Galata Tower Çok Güzel+1.jpg");
        assertThat(OsmPlaceMapper.commonsFile(null,
                "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4a/Moda_Sahili.jpg/800px-Moda_Sahili.jpg"))
                .isEqualTo("Moda Sahili.jpg");
        assertThat(OsmPlaceMapper.commonsFile(null,
                "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/27/Galata.jpg/960px-Galata.jpg?utm_source=x"))
                .isEqualTo("Galata.jpg");
        assertThat(OsmPlaceMapper.commonsFile(null,
                "http://upload.wikimedia.org/wikipedia/commons/4/4a/Moda.JPG")).isEqualTo("Moda.JPG");

        // Unknown license: other hosts, other wikis' local files (may be fair use), Commons categories
        assertThat(OsmPlaceMapper.commonsFile(null, "https://example.com/Moda.jpg")).isNull();
        assertThat(OsmPlaceMapper.commonsFile(null, "https://upload.wikimedia.org/wikipedia/en/4/4a/Logo.jpg")).isNull();
        assertThat(OsmPlaceMapper.commonsFile(null, "https://commons.wikimedia.org/wiki/Category:Moda")).isNull();
        assertThat(OsmPlaceMapper.commonsFile(null, "https://www.flickr.com/photos/x/123")).isNull();
        assertThat(OsmPlaceMapper.commonsFile("File:", null)).isNull();
        assertThat(OsmPlaceMapper.commonsFile("File:A|B.jpg", null)).isNull();
    }

    @Test
    void wikidataMustBeASingleItemId() {
        assertThat(OsmPlaceMapper.wikidata("Q42")).isEqualTo("Q42");
        assertThat(OsmPlaceMapper.wikidata("q42")).isNull();
        assertThat(OsmPlaceMapper.wikidata("Q42;Q43")).isNull();
        assertThat(OsmPlaceMapper.wikidata("P18")).isNull();
        assertThat(OsmPlaceMapper.wikidata("")).isNull();
        assertThat(OsmPlaceMapper.wikidata(null)).isNull();
    }

    @Test
    void foldIgnoresCaseTurkishLettersAndPunctuation() {
        assertThat(OsmPlaceMapper.fold("Çiya Sofrası")).isEqualTo("ciyasofrasi");
        assertThat(OsmPlaceMapper.fold("KAHVALTI")).isEqualTo("kahvalti");
        assertThat(OsmPlaceMapper.fold("İstanbul Modern")).isEqualTo("istanbulmodern");
        assertThat(OsmPlaceMapper.fold("Walter's Coffee")).isEqualTo(OsmPlaceMapper.fold("Walters coffee"));
    }
}

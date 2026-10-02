package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.overture.OvertureMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Explore > Kahvaltı near Çayırova was empty while "Börek Diyarı" and "Bayındır Unlu Mamülleri" were listed as cafés
class BreakfastPlacesTest {

    @Test
    void borekShopsAndSavouryBakeriesAreBreakfastPlaces() {
        assertThat(PlaceTags.breakfastAware(PlaceCategory.CAFE, OsmPlaceMapper.fold("Börek Diyarı"),
                new ArrayList<>(List.of("bakery")))).isEqualTo(PlaceCategory.BREAKFAST);
        assertThat(PlaceTags.breakfastAware(PlaceCategory.CAFE, OsmPlaceMapper.fold("Bayındır Unlu Mamülleri"),
                new ArrayList<>())).isEqualTo(PlaceCategory.BREAKFAST);
        assertThat(PlaceTags.breakfastAware(PlaceCategory.RESTAURANT, OsmPlaceMapper.fold("Poğaçacı Ali"),
                new ArrayList<>(List.of("quick")))).isEqualTo(PlaceCategory.BREAKFAST);
        // An ordinary café or kıraathane stays a café
        assertThat(PlaceTags.breakfastAware(PlaceCategory.CAFE, OsmPlaceMapper.fold("Joker Kıraathanesi"),
                new ArrayList<>())).isEqualTo(PlaceCategory.CAFE);
        assertThat(PlaceTags.breakfastAware(PlaceCategory.RESTAURANT, OsmPlaceMapper.fold("Köfteci Yusuf"),
                new ArrayList<>(List.of("quick")))).isEqualTo(PlaceCategory.RESTAURANT);
    }

    @Test
    void breakfastCafesAreListedUnderBoth() {
        List<String> tags = new ArrayList<>();
        assertThat(PlaceTags.breakfastAware(PlaceCategory.BREAKFAST, OsmPlaceMapper.fold("Ayaz Cafe&Restaurant"), tags))
                .isEqualTo(PlaceCategory.BREAKFAST);
        assertThat(tags).contains("breakfast", "cafe");

        // An OSM café serving breakfast
        OsmPlaceMapper.OsmPlace cafe = OsmPlaceMapper.map(element("amenity", "cafe", "cuisine", "breakfast",
                "name", "Moda Sabah"));
        assertThat(cafe.category()).isEqualTo(PlaceCategory.BREAKFAST);
        assertThat(cafe.tags()).contains("breakfast", "cafe");

        OsmPlaceMapper.OsmPlace borek = OsmPlaceMapper.map(element("shop", "bakery", "name", "Börek Diyarı"));
        assertThat(borek.category()).isEqualTo(PlaceCategory.BREAKFAST);
        assertThat(borek.tags()).contains("breakfast");
    }

    @Test
    void overtureBakeryIsABreakfastPlace() {
        OvertureMapper.OverturePlace place = OvertureMapper.map(new OvertureMapper.OvertureRow("o-1",
                "Bayındır Unlu Mamülleri", List.of("food_and_drink", "bakery"), 0.9, null, null, null, 40.81, 29.37));
        assertThat(place.category()).isEqualTo(PlaceCategory.BREAKFAST);
        assertThat(place.tags()).contains("breakfast", "bakery");
    }

    private static OverpassResponse.Element element(String... keyValues) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            tags.put(keyValues[i], keyValues[i + 1]);
        }
        return new OverpassResponse.Element("node", 1, 40.81, 29.37, null, tags, null, null, null);
    }
}

package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.overture.OvertureMapper.OverturePlace;
import com.nomi.wayfinder.overture.OvertureMapper.OvertureRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OvertureMapperTest {

    @Test
    void mapsRestaurantsCafesBakeriesAndDessertsLikeOsm() {
        assertThat(category("Devran Kebap", "food_and_drink/restaurant/middle_eastern_restaurant/turkish_restaurant"))
                .isEqualTo(PlaceCategory.RESTAURANT);
        assertThat(category("Big Fenomen", "food_and_drink/casual_eatery/cafe")).isEqualTo(PlaceCategory.CAFE);
        assertThat(category("Mola Cafefood", "food_and_drink/non_alcoholic_beverage_venue/coffee_shop"))
                .isEqualTo(PlaceCategory.CAFE);
        assertThat(category("Gümüş Dondurma", "food_and_drink/casual_eatery/dessert_shop/ice_cream_shop"))
                .isEqualTo(PlaceCategory.DESSERT);
        assertThat(category("Çiğdem Pastanesi", "food_and_drink/casual_eatery/bakery")).isEqualTo(PlaceCategory.DESSERT);
        assertThat(category("Bayındır Unlu Mamülleri", "food_and_drink/casual_eatery/bakery"))
                .isEqualTo(PlaceCategory.CAFE);
        assertThat(category("Serpme Keyfi", "food_and_drink/restaurant/breakfast_and_brunch_restaurant"))
                .isEqualTo(PlaceCategory.BREAKFAST);
    }

    @Test
    void fastFoodIsAQuickMealAndCuisineGivesInterestTags() {
        OverturePlace doner = map(row("Paşa Döner Çayırova", "food_and_drink/casual_eatery/fast_food_restaurant"));
        OverturePlace fish = map(row("Emirhan Balıkçılık", "food_and_drink/restaurant/seafood_restaurant"));
        OverturePlace turkish = map(row("Salim Baba Sofrası",
                "food_and_drink/restaurant/middle_eastern_restaurant/turkish_restaurant"));

        assertThat(doner.category()).isEqualTo(PlaceCategory.RESTAURANT);
        assertThat(doner.tags()).contains("quick", "budget");
        assertThat(fish.tags()).contains("seafood");
        assertThat(turkish.tags()).contains("local");
    }

    @Test
    void aMealInTheNameBeatsAWrongCafeTaxonomy() {
        OverturePlace doner = map(row("Gözde Cağ Döner", "food_and_drink/non_alcoholic_beverage_venue/coffee_shop"));
        assertThat(doner.category()).isEqualTo(PlaceCategory.RESTAURANT);
        assertThat(doner.tags()).contains("quick");
        // A café that also sells tost / döner stays a café; a pastry shop named Çağla stays a dessert place
        assertThat(category("Döner Cafe", "food_and_drink/casual_eatery/cafe")).isEqualTo(PlaceCategory.CAFE);
        assertThat(category("Çağla Pastanesi", "food_and_drink/casual_eatery/bakery")).isEqualTo(PlaceCategory.DESSERT);
    }

    @Test
    void teaGardensAreOutdoorTeaCafes() {
        OverturePlace garden = map(row("Çayırova Kızılay Çay Bahçesi", "food_and_drink/casual_eatery/cafe"));

        assertThat(garden.category()).isEqualTo(PlaceCategory.CAFE);
        assertThat(garden.tags()).contains("tea");
        assertThat(garden.indoor()).isFalse();
    }

    @Test
    void skipsBarsCanteensInternetCafesBreadOvensAndNonFood() {
        assertThat(map(row("Bira Evi", "food_and_drink/alcoholic_beverage_venue/bar"))).isNull();
        assertThat(map(row("Fabrika Yemekhane", "food_and_drink/restaurant/cafeteria"))).isNull();
        assertThat(map(row("Şeker Cafe Ps3", "food_and_drink/casual_eatery/cafe/internet_cafe"))).isNull();
        assertThat(map(row("Yıldız Ekmek Fırını", "food_and_drink/casual_eatery/bakery"))).isNull();
        assertThat(map(row("Kelebek Mobilya", "shopping/home_and_garden_store"))).isNull();
    }

    @Test
    void skipsClosedUnnamedAndUnrealisticPlaces() {
        assertThat(map(new OvertureRow("a", "Kapanan Lokanta", List.of("food_and_drink", "restaurant"), 0.9,
                "permanently_closed", null, null, 40.8, 29.37))).isNull();
        assertThat(map(row(" ", "food_and_drink/restaurant"))).isNull();
        // A school canteen (osm/PlaceRealismFilter)
        assertThat(map(row("Atatürk İlkokulu Kantini", "food_and_drink/casual_eatery/cafe"))).isNull();
        // Outside Turkey
        assertThat(map(new OvertureRow("b", "Cafe", List.of("food_and_drink", "casual_eatery", "cafe"), 0.9,
                null, null, null, 48.8, 2.3))).isNull();
    }

    @Test
    void cleansNamesAndKeepsOnlyWebLinks() {
        OverturePlace place = map(new OvertureRow("c", "HASAT UNLU MAMÜLLERİ",
                List.of("food_and_drink", "casual_eatery", "bakery"), 0.8, "open", "+90 262 000 00 00",
                "javascript:alert(1)", 40.8, 29.37));

        assertThat(place.name()).isEqualTo("Hasat Unlu Mamülleri");
        assertThat(place.phone()).isEqualTo("+90 262 000 00 00");
        assertThat(place.website()).isNull();
        assertThat(OvertureMapper.website("https://example.com/menu")).isEqualTo("https://example.com/menu");
    }

    @Test
    void fixesWordsTypedWithAStuckShiftKeyButKeepsBrandCasing() {
        assertThat(OvertureMapper.fixMixedCase("HalİSbey Et Lokantasi")).isEqualTo("Halisbey Et Lokantasi");
        assertThat(OvertureMapper.fixMixedCase("Glows Waffle ÇAyirova")).isEqualTo("Glows Waffle Çayirova");
        assertThat(OvertureMapper.fixMixedCase("McDonald's")).isEqualTo("McDonald's");
        assertThat(OvertureMapper.fixMixedCase("KFC Gebze")).isEqualTo("KFC Gebze");
    }

    private static PlaceCategory category(String name, String hierarchy) {
        OverturePlace place = map(row(name, hierarchy));
        return place == null ? null : place.category();
    }

    private static OverturePlace map(OvertureRow row) {
        return OvertureMapper.map(row);
    }

    private static OvertureRow row(String name, String hierarchy) {
        return new OvertureRow("id-" + name, name, List.of(hierarchy.split("/")), 0.9, null, null, null, 40.8161,
                29.3756);
    }
}

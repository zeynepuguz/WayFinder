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
        // Savoury bakeries are breakfast places
        assertThat(category("Bayındır Unlu Mamülleri", "food_and_drink/casual_eatery/bakery"))
                .isEqualTo(PlaceCategory.BREAKFAST);
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
    void marketsAndPlacesOfWorshipHaveTheirOwnCategories() {
        assertThat(category("BİM", "shopping/food_and_beverage_store/grocery_store")).isEqualTo(PlaceCategory.MARKET);
        assertThat(category("Dolayoba Gözdağı Cami", "cultural_and_historic/place_of_worship/muslim_place_of_worship"))
                .isEqualTo(PlaceCategory.WORSHIP);
        // A page named after its address is no place name; a café called "Mahalle" is
        assertThat(map(row("Hocaalizade Mah Osmangazi Bursa", "shopping/food_and_beverage_store/grocery_store")))
                .isNull();
        assertThat(map(row("Atatürk Cad. No: 5", "food_and_drink/casual_eatery/cafe"))).isNull();
        assertThat(category("Mahalle Kahvesi Moda", "food_and_drink/casual_eatery/cafe")).isEqualTo(PlaceCategory.CAFE);
        // Market chains however their page is filed; ordinary words that are also chain names only for shops
        assertThat(category("Mimar Sinan Hakmar", "shopping/shopping_mall")).isEqualTo(PlaceCategory.MARKET);
        assertThat(category("Migros Mjet Çayırova", "shopping/superstore")).isEqualTo(PlaceCategory.MARKET);
        assertThat(category("A 101 Çayırova", "shopping/discount_store")).isEqualTo(PlaceCategory.MARKET);
        assertThat(category("Onur Pastanesi", "food_and_drink/casual_eatery/bakery")).isEqualTo(PlaceCategory.DESSERT);
        assertThat(category("Yunus Emre Camii", "cultural_and_historic/place_of_worship/muslim_place_of_worship"))
                .isEqualTo(PlaceCategory.WORSHIP);
        assertThat(map(row("Forum Istanbul", "shopping/shopping_mall"))).isNull();
        // "<street> <district> <province>" is an address; a chain branch named that way is a market
        assertThat(map(row("Yeni Bağdat Gebze Kocaeli", "shopping/food_and_beverage_store/grocery_store"))).isNull();
        assertThat(category("BİM Gebze Kocaeli", "shopping/food_and_beverage_store/grocery_store"))
                .isEqualTo(PlaceCategory.MARKET);
        // A market named after pide stays a market
        assertThat(category("Pide Market", "shopping/food_and_beverage_store/grocery_store"))
                .isEqualTo(PlaceCategory.MARKET);
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

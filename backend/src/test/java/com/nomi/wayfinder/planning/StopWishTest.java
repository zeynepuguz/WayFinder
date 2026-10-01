package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// "Başka bir yerle değiştir" > "Neye göre?": the user's words decide what the new place should be like
class StopWishTest {

    @Test
    void readsFoodWordsPriceDistanceAndIndoor() {
        StopWish kebap = StopWish.parse("Kebap olsun");
        assertThat(kebap.words()).contains("kebap", "kebab");
        assertThat(kebap.cheaper()).isFalse();

        StopWish cheaper = StopWish.parse("Daha ucuz bir yer");
        assertThat(cheaper.cheaper()).isTrue();
        assertThat(cheaper.words()).isEmpty();

        assertThat(StopWish.parse("Daha yakın").closer()).isTrue();
        assertThat(StopWish.parse("Daha yakın").hasCriteria()).isFalse();
        assertThat(StopWish.parse("Kapalı alan").indoor()).isTrue();
        assertThat(StopWish.parse("Bahçeli").indoor()).isFalse();
        assertThat(StopWish.parse("Deniz manzaralı").tags()).contains("sea", "view");
        assertThat(StopWish.parse("balıkçı").words()).contains("balik", "fish");
        assertThat(StopWish.parse("  ")).isNull();
    }

    @Test
    void matchesNameCuisineTagsPriceAndIndoor() {
        Place kebapci = place("Köfteci Yusuf Kebapçısı", null, List.of("local"), 300, true);
        Place cafe = place("Moda Kafe", "coffee_shop", List.of(), 150, false);

        assertThat(StopWish.parse("kebap").matches(kebapci)).isTrue();
        assertThat(StopWish.parse("kebap").matches(cafe)).isFalse();
        assertThat(StopWish.parse("kapalı alan").matches(kebapci)).isTrue();
        assertThat(StopWish.parse("kapalı alan").matches(cafe)).isFalse();

        StopWish cheaper = StopWish.parse("daha ucuz").replacing(250);
        assertThat(cheaper.matches(cafe)).isTrue();
        assertThat(cheaper.matches(kebapci)).isFalse();
    }

    private static Place place(String name, String cuisine, List<String> tags, Integer cost, boolean indoor) {
        Place place = new Place();
        place.setName(name);
        place.setTags(tags);
        place.setEstimatedCost(cost);
        place.setIndoor(indoor);
        place.setCategory(PlaceCategory.RESTAURANT);
        return place;
    }
}

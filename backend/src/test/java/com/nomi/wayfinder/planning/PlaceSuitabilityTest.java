package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.PlaceCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceSuitabilityTest {

    @Test
    void takeAwayBakeriesAndTeaHousesAreNotStops() {
        assertThat(stop("Bayındır Unlu Mamülleri", PlaceCategory.CAFE, "bakery")).isFalse();
        assertThat(stop("Yıldız Ekmek Fırını", PlaceCategory.CAFE)).isFalse();
        assertThat(stop("Joker Kıraathanesi", PlaceCategory.CAFE, "coffee")).isFalse();
        assertThat(stop("Merkez Kahvehanesi", PlaceCategory.CAFE)).isFalse();
        // A simit counter filed as a quick meal
        assertThat(stop("Sıcak Simit", PlaceCategory.RESTAURANT, "quick")).isFalse();
        assertThat(stop("Çıtır Simit Taner İlter", PlaceCategory.RESTAURANT)).isFalse();
        assertThat(stop("Dudhar Köme Pestil Çayırova", PlaceCategory.DESSERT)).isFalse();
    }

    @Test
    void placesWithTablesStay() {
        assertThat(stop("Tuana Börek", PlaceCategory.CAFE, "bakery")).isTrue();
        assertThat(stop("Simit Sarayı", PlaceCategory.CAFE, "bakery")).isTrue();
        assertThat(stop("Hasat Fırın Cafe", PlaceCategory.CAFE, "bakery")).isTrue();
        assertThat(stop("Çiğdem Pastanesi", PlaceCategory.DESSERT)).isTrue();
        assertThat(stop("Karadeniz Pide Fırını", PlaceCategory.RESTAURANT)).isTrue();
        assertThat(stop("Big Fenomen", PlaceCategory.CAFE)).isTrue();
        assertThat(stop("Devran Kebap", PlaceCategory.RESTAURANT, "quick", "local")).isTrue();
    }

    private static boolean stop(String name, PlaceCategory category, String... tags) {
        return PlaceSuitability.isStop(name, category, List.of(tags));
    }

    @Test
    void teaHousesAndCoffeeShopsAreNoMeal() {
        assertThat(PlaceSuitability.servesMeals(
                com.nomi.wayfinder.TestPlaces.place(1, "Yıldız Çay Evi", PlaceCategory.CAFE, true, 4.0, 0))).isFalse();
        assertThat(PlaceSuitability.servesMeals(
                com.nomi.wayfinder.TestPlaces.place(2, "Mola", PlaceCategory.CAFE, true, 4.0, 0, "coffee"))).isFalse();
        assertThat(PlaceSuitability.servesMeals(
                com.nomi.wayfinder.TestPlaces.place(3, "İnchiborek", PlaceCategory.CAFE, true, 4.0, 0, "bakery"))).isTrue();
        assertThat(PlaceSuitability.servesMeals(
                com.nomi.wayfinder.TestPlaces.place(4, "Kebapçı", PlaceCategory.RESTAURANT, true, 4.0, 0))).isTrue();
    }
}

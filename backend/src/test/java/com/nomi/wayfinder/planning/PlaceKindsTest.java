package com.nomi.wayfinder.planning;

import com.nomi.wayfinder.entity.PlaceCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceKindsTest {

    @Test
    void theNameTellsTheKind() {
        assertThat(kind("Deniz Balık Restaurant", PlaceCategory.RESTAURANT)).isEqualTo("FISH");
        assertThat(kind("Tarihi Cağ Kebap Salonu", PlaceCategory.RESTAURANT)).isEqualTo("KEBAB");
        assertThat(kind("Öz Urfa Lahmacun", PlaceCategory.RESTAURANT)).isEqualTo("PIDE");
        assertThat(kind("Komagene Çiğ Köfte", PlaceCategory.RESTAURANT)).isEqualTo("CIGKOFTE");
        assertThat(kind("Sultanahmet Köftecisi", PlaceCategory.RESTAURANT)).isEqualTo("KOFTE");
        assertThat(kind("Çiğdem Pastanesi", PlaceCategory.DESSERT)).isEqualTo("PASTRY");
        assertThat(kind("Hafız Mustafa Baklava", PlaceCategory.DESSERT)).isEqualTo("BAKLAVA");
        assertThat(kind("Emirgan Çay Bahçesi", PlaceCategory.CAFE)).isEqualTo("TEA_HOUSE");
        assertThat(kind("Kahve Dünyası", PlaceCategory.CAFE)).isEqualTo("COFFEE");
        assertThat(kind("Joker Kıraathanesi", PlaceCategory.CAFE)).isEqualTo("KIRAATHANE");
        // An internet / game café is that, whatever else is in the name
        assertThat(kind("Cadde Arena Cafe", PlaceCategory.CAFE)).isEqualTo("GAME_CAFE");
    }

    @Test
    void cuisineAndTagsHelpWhenTheNameDoesNot() {
        assertThat(PlaceKinds.of("Karadeniz Sofrası", PlaceCategory.RESTAURANT, "turkish;fish", List.of()))
                .isEqualTo("FISH");
        assertThat(PlaceKinds.of("Mavi Ev", PlaceCategory.CAFE, "coffee_shop", List.of())).isEqualTo("COFFEE");
        assertThat(PlaceKinds.of("Hasat", PlaceCategory.BREAKFAST, null, List.of("bakery"))).isEqualTo("BAKERY");
    }

    @Test
    void lookAlikesAndOtherCategoriesHaveNoKind() {
        // "Balıkesir" is a city, "Romantik" not a mantı place
        assertThat(kind("Balıkesir Sofrası", PlaceCategory.RESTAURANT)).isNull();
        assertThat(kind("Romantik Cafe", PlaceCategory.CAFE)).isNull();
        // Only the category is known
        assertThat(kind("Nar Cafe", PlaceCategory.CAFE)).isNull();
        assertThat(kind("Balıklı Göl", PlaceCategory.ATTRACTION)).isNull();
    }

    private static String kind(String name, PlaceCategory category) {
        return PlaceKinds.of(name, category, null, List.of());
    }
}

package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.overture.OvertureMapper.OverturePlace;
import com.nomi.wayfinder.overture.OvertureMapper.OvertureRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Real Çayırova rows: A101's Meta branch pages are named after their street / quarter, the brand says what they are
class OvertureBrandTest {

    private static final List<String> GROCERY = List.of("shopping", "food_and_beverage_store", "grocery_store");

    @Test
    void branchPageNamedAfterItsAddressIsTheChainsShop() {
        OverturePlace a101 = OvertureMapper.map(row("Yeni Bağdat Gebze Kocaeli", "A101", "Q6034496"));
        assertThat(a101.name()).isEqualTo("A101 Yeni Bağdat Gebze");
        assertThat(a101.category()).isEqualTo(PlaceCategory.MARKET);

        assertThat(OvertureMapper.map(row("210 Sokak Çayırova Kocaeli", "A101", "Q6034496")).name())
                .isEqualTo("A101 210 Sokak Çayırova");
        // Already named so
        assertThat(OvertureMapper.map(row("Şok Mini", "Şok", "Q19613992")).name()).isEqualTo("Şok Mini");
    }

    @Test
    void withoutASureBrandAnAddressNameIsStillDropped() {
        assertThat(OvertureMapper.map(row("Yeni Bağdat Gebze Kocaeli", null, null))).isNull();
        // A brand without a Wikidata item is a page owner's guess ("Garanti BBVA" on a petrol station)
        assertThat(OvertureMapper.map(row("Yeni Bağdat Gebze Kocaeli", "Migros Türkiye", null))).isNull();
    }

    private static OvertureRow row(String name, String brand, String wikidata) {
        return new OvertureRow("o-" + name.hashCode(), name, GROCERY, 0.8, null, null, null, 40.8158, 29.3748,
                brand, wikidata);
    }
}

package com.nomi.wayfinder.area;

import com.nomi.wayfinder.area.NamedArea.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bug of 2026-10-15: "of ilçesinden" and "trabzon of" were not understood (Of, Trabzon has 2 letters and short
 * names were left out), and "bir günlük rota planla" started a route in the Günlük neighbourhood.
 */
class ShortDistrictNameTest {

    private final AreaMatcher matcher = new AreaMatcher(List.of(
            new NamedArea("Trabzon", Kind.CITY, 41.00, 39.72, null, "Trabzon"),
            new NamedArea("Of", Kind.DISTRICT, 40.95, 40.27, "Of", "Trabzon"),
            new NamedArea("Akçaabat", Kind.DISTRICT, 41.02, 39.57, "Akçaabat", "Trabzon"),
            new NamedArea("Niğde", Kind.CITY, 37.97, 34.68, null, "Niğde"),
            new NamedArea("Bor", Kind.DISTRICT, 37.89, 34.56, "Bor", "Niğde"),
            new NamedArea("Günlük", Kind.AREA, 40.8, 29.4, null, "Kocaeli")));

    private String name(String text) {
        return matcher.find(text).map(NamedArea::name).orElse(null);
    }

    @Test
    void aShortDistrictCountsWhenTheMessageSaysItIsTheDistrict() {
        assertThat(name("of ilçesinden")).isEqualTo("Of");
        assertThat(name("Of ilçesinde kahvaltı")).isEqualTo("Of");
        assertThat(name("bor ilçesi")).isEqualTo("Bor");
    }

    @Test
    void aShortDistrictCountsNextToItsCity() {
        assertThat(name("trabzon of")).isEqualTo("Of");
        assertThat(name("Of, Trabzon")).isEqualTo("Of");
        assertThat(name("Trabzon Of'ta gezelim")).isEqualTo("Of");
    }

    @Test
    void theAiServicesAreaFieldIsJustTheName() {
        assertThat(name("Of")).isEqualTo("Of");
        assertThat(name("Of'tan")).isEqualTo("Of");
    }

    @Test
    void elsewhereAShortNameIsAnEverydayWord() {
        // English "of", Turkish "bor" (boron) in a sentence: no place
        assertThat(name("a day of walking and coffee")).isNull();
        assertThat(name("bor madeni müzesi var mı")).isNull();
        // the city alone stays the city
        assertThat(name("trabzonda bir gün")).isEqualTo("Trabzon");
    }

    @Test
    void aDaysRouteIsNoTripToGunluk() {
        Optional<NamedArea> found = matcher.find("Tarihi yerler ve müzeler ağırlıklı bir günlük rota planla");
        assertThat(found).isEmpty();
    }
}

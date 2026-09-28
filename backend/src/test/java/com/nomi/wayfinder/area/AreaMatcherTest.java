package com.nomi.wayfinder.area;

import com.nomi.wayfinder.area.NamedArea.Kind;
import com.nomi.wayfinder.dto.DistrictResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AreaMatcherTest {

    private final AreaMatcher matcher = new AreaMatcher(List.of(
            district("Üsküdar", 41.026, 29.016),
            district("Kadıköy", 40.990, 29.028),
            district("Beşiktaş", 41.043, 29.007),
            district("Şile", 41.176, 29.612),
            area("Moda", 40.982, 29.025),
            area("Bebek", 41.077, 29.043),
            area("Kuzguncuk", 41.036, 29.031),
            area("Üsküdar", 41.024, 29.015),
            // Common word, and a name used in several places far apart
            area("Merkez", 41.0, 29.0),
            area("Cumhuriyet", 41.05, 28.9),
            area("Cumhuriyet", 40.9, 29.3),
            area("Göztepe", 40.97, 29.06),
            area("Göztepe", 41.08, 29.06)));

    @Test
    void findsDistrictsWithTurkishSuffixes() {
        assertThat(name("ben yarın 2 kişi 700 tl ile üsküdarda gezeceğiz")).isEqualTo("Üsküdar");
        assertThat(name("Üsküdar'da buluşalım")).isEqualTo("Üsküdar");
        assertThat(name("kadıköy'e gidiyoruz")).isEqualTo("Kadıköy");
        assertThat(name("Kadikoy'deyim")).isEqualTo("Kadıköy");
        assertThat(name("BEŞİKTAŞ'TAN başlayalım")).isEqualTo("Beşiktaş");
        assertThat(name("a day in Kadikoy")).isEqualTo("Kadıköy");
    }

    @Test
    void findsNeighbourhoods() {
        assertThat(name("moda'da kahve içelim")).isEqualTo("Moda");
        assertThat(name("bebekte yürüyüş")).isEqualTo("Bebek");
        assertThat(name("Kuzguncuk")).isEqualTo("Kuzguncuk");
    }

    @Test
    void districtWinsOverANeighbourhoodWithTheSameName() {
        assertThat(matcher.find("üsküdarda").orElseThrow().kind()).isEqualTo(Kind.DISTRICT);
    }

    @Test
    void longestNameWins() {
        AreaMatcher m = new AreaMatcher(List.of(area("Kuzgun", 41.0, 29.0), area("Kuzguncuk", 41.036, 29.031)));

        assertThat(m.find("kuzguncukta").orElseThrow().name()).isEqualTo("Kuzguncuk");
    }

    @Test
    void multiWordNamesMatchConsecutiveWords() {
        AreaMatcher m = new AreaMatcher(List.of(area("Bağdat Caddesi", 40.96, 29.07), area("Caddebostan", 40.97, 29.06)));

        assertThat(m.find("bağdat caddesinde gezelim").orElseThrow().name()).isEqualTo("Bağdat Caddesi");
        assertThat(m.find("bağdat'a gidelim")).isEmpty();
    }

    @Test
    void doesNotMatchInsideOtherWordsOrWithNonCaseEndings() {
        assertThat(matcher.find("bunu silebilir misin")).isEmpty();
        assertThat(matcher.find("bebekli bir aileyiz")).isEmpty();
        assertThat(matcher.find("modaya uygun değil")).map(NamedArea::name).contains("Moda");
        assertThat(matcher.find("merkeze yakın bir yer")).isEmpty();
        assertThat(matcher.find("cumhuriyet'te")).isEmpty();
        assertThat(matcher.find("göztepe'de")).isEmpty();
        assertThat(matcher.find("Ankara'da gezeceğiz")).isEmpty();
        assertThat(matcher.find(null)).isEmpty();
    }

    @Test
    void emptyListFindsNothing() {
        assertThat(new AreaMatcher(List.of()).find("üsküdarda")).isEmpty();
    }

    @Test
    void districtsAreSortedTheTurkishWay() {
        List<DistrictResponse> sorted = DistrictService.sortByName(List.of(
                response("Şişli"), response("Sultangazi"), response("Çatalca"), response("Üsküdar"),
                response("Büyükçekmece"), response("Ümraniye"), response("Ataşehir")));

        assertThat(sorted).extracting(DistrictResponse::name).containsExactly(
                "Ataşehir", "Büyükçekmece", "Çatalca", "Sultangazi", "Şişli", "Ümraniye", "Üsküdar");
    }

    private String name(String message) {
        return matcher.find(message).map(NamedArea::name).orElse(null);
    }

    private static NamedArea district(String name, double lat, double lon) {
        return new NamedArea(name, Kind.DISTRICT, lat, lon, name);
    }

    private static NamedArea area(String name, double lat, double lon) {
        return new NamedArea(name, Kind.AREA, lat, lon, null);
    }

    private static DistrictResponse response(String name) {
        return new DistrictResponse(name.toLowerCase(), name, 41, 29, null, null, null, null, 0);
    }
}

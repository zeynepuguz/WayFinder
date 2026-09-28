package com.nomi.wayfinder.area;

import com.nomi.wayfinder.area.NamedArea.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Cities and the districts / neighbourhoods of several cities in one matcher
class CityAreaMatcherTest {

    private static final String ISTANBUL = "İstanbul";
    private static final String ANKARA = "Ankara";
    private static final String IZMIR = "İzmir";
    private static final String BURSA = "Bursa";
    private static final String MERSIN = "Mersin";

    private final AreaMatcher matcher = new AreaMatcher(List.of(
            city(ISTANBUL, 41.01, 28.97),
            city(ANKARA, 39.92, 32.85),
            city(IZMIR, 38.42, 27.14),
            city(BURSA, 40.19, 29.06),
            city(MERSIN, 36.80, 34.63),
            city("Van", 38.50, 43.38),
            district("Üsküdar", ISTANBUL, 41.026, 29.016),
            district("Kadıköy", ISTANBUL, 40.990, 29.028),
            district("Çankaya", ANKARA, 39.90, 32.86),
            district("Konak", IZMIR, 38.41, 27.13),
            // The same district name in two cities
            district("Yenişehir", BURSA, 40.26, 29.65),
            district("Yenişehir", MERSIN, 36.79, 34.58),
            // A central district called "Merkez" (common word)
            district("Merkez", "Bolu", 40.73, 31.60),
            area("Kızılay", ANKARA, 39.9208, 32.8541),
            area("Alsancak", IZMIR, 38.4378, 27.1437),
            area("Moda", ISTANBUL, 40.982, 29.025),
            // A neighbourhood name used in two cities
            area("Bahçelievler", ISTANBUL, 41.00, 28.86),
            area("Bahçelievler", ANKARA, 39.92, 32.82)));

    @Test
    void cityNameAloneMeansTheCityLabelPoint() {
        NamedArea ankara = matcher.find("Ankara'da gezeceğiz").orElseThrow();
        assertThat(ankara.kind()).isEqualTo(Kind.CITY);
        assertThat(ankara.latitude()).isEqualTo(39.92);
        assertThat(name("İzmir'e gidiyoruz")).isEqualTo(IZMIR);
        assertThat(name("a day in Antalya")).isNull();
        assertThat(name("in Izmir tomorrow")).isEqualTo(IZMIR);
        assertThat(name("Van'da kahvaltı")).isEqualTo("Van");
    }

    @Test
    void aNeighbourhoodOfTheNamedCityWinsOverTheCity() {
        NamedArea kizilay = matcher.find("yarın Ankara'da Kızılay'dan başlayan bir rota", ISTANBUL).orElseThrow();
        assertThat(kizilay.name()).isEqualTo("Kızılay");
        assertThat(kizilay.city()).isEqualTo(ANKARA);
        assertThat(name("İzmir Konak'ta bir gün")).isEqualTo("Konak");
        // A district of another city does not refine the named city
        assertThat(name("Ankara'da, Kadıköy gibi bir yer")).isEqualTo(ANKARA);
    }

    @Test
    void uniqueNamesWorkNationwideAndAmbiguousOnesNeedAContext() {
        // Unique: wherever the user is
        assertThat(matcher.find("Kızılay'da buluşalım", ISTANBUL).map(NamedArea::name)).contains("Kızılay");
        assertThat(matcher.find("Üsküdar'da", null).map(NamedArea::name)).contains("Üsküdar");
        // Two cities have a Yenişehir: the user's city, or the named one, else nothing
        assertThat(matcher.find("Yenişehir'de gezelim", BURSA).map(NamedArea::city)).contains(BURSA);
        assertThat(matcher.find("Yenişehir'de gezelim", MERSIN).map(NamedArea::city)).contains(MERSIN);
        assertThat(matcher.find("Mersin Yenişehir'de", BURSA).map(NamedArea::city)).contains(MERSIN);
        assertThat(matcher.find("Yenişehir'de gezelim", ISTANBUL)).isEmpty();
        assertThat(matcher.find("Yenişehir'de gezelim", null)).isEmpty();
        // Same for neighbourhoods
        assertThat(matcher.find("bahçelievlerde", ANKARA).map(NamedArea::city)).contains(ANKARA);
        assertThat(matcher.find("bahçelievlerde", ISTANBUL).map(NamedArea::city)).contains(ISTANBUL);
    }

    @Test
    void commonWordsAreNotDistrictsAnywhere() {
        assertThat(matcher.find("merkeze yakın bir yer", "Bolu")).isEmpty();
    }

    @Test
    void istanbulBehaviourStays() {
        assertThat(matcher.find("ben yarın 2 kişi 700 tl ile üsküdarda gezeceğiz", ISTANBUL).map(NamedArea::name))
                .contains("Üsküdar");
        assertThat(matcher.find("moda'da kahve içelim", ANKARA).map(NamedArea::name)).contains("Moda");
        assertThat(matcher.find("bunu silebilir misin", ISTANBUL)).isEmpty();
    }

    private String name(String message) {
        return matcher.find(message).map(NamedArea::name).orElse(null);
    }

    private static NamedArea city(String name, double lat, double lon) {
        return new NamedArea(name, Kind.CITY, lat, lon, null, name);
    }

    private static NamedArea district(String name, String city, double lat, double lon) {
        return new NamedArea(name, Kind.DISTRICT, lat, lon, name, city);
    }

    private static NamedArea area(String name, String city, double lat, double lon) {
        return new NamedArea(name, Kind.AREA, lat, lon, null, city);
    }
}

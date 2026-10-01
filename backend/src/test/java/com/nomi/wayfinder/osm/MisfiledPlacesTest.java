package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.overture.OvertureMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Real names from Kocaeli and other cities that showed up under the wrong category (İbadet > Kilise, Gezilecek yer)
class MisfiledPlacesTest {

    @Test
    void overtureWorshipPagesMustBePlacesToPray() {
        // Meta pages filed as christian places of worship in Kocaeli
        assertThat(overtureWorship("Fatih Mezarlığı")).isNull();
        assertThat(overtureWorship("Rahmiye Kuran Kursu")).isNull();
        assertThat(overtureWorship("Yenidogan cafe")).isNull();
        assertThat(overtureWorship("Sarıbey Köyü")).isNull();
        assertThat(overtureWorship("Şair Ahmet Paşa Türbesi")).isNull();
        assertThat(overtureWorship("Cami Yaptırma ve Yaşatma Derneği")).isNull();

        // The kind comes from the name, not from Overture's religion
        OvertureMapper.OverturePlace church = overtureWorship("İzmit Protestan Kilisesi");
        assertThat(church.category()).isEqualTo(PlaceCategory.WORSHIP);
        assertThat(church.tags()).contains("church");
        assertThat(overtureWorship("Orhan Gazi Camii").tags()).contains("mosque").doesNotContain("church");
    }

    @Test
    void lastTellingWordDecides() {
        assertThat(PlaceTags.placeToPray("Fatih Camii Kuran Kursu", false)).isFalse();
        assertThat(PlaceTags.placeToPray("Hacı Bektaş Veli Kültür Vakfı Çınarlı Cemevi", true)).isTrue();
        assertThat(PlaceTags.placeToPray("Kurşunlu Camii", true)).isTrue();
        assertThat(PlaceTags.placeToPray("Söke Cemevi Cafer Otan Hayratı", true)).isTrue();
        // OSM place_of_worship without a worship word stays (a synagogue called "Neve Şalom")
        assertThat(PlaceTags.placeToPray("Neve Şalom", false)).isTrue();
        assertThat(PlaceTags.placeToPray("Neve Şalom", true)).isFalse();
    }

    @Test
    void osmCemeteryOrCourseFiledAsWorshipIsDropped() {
        assertThat(OsmPlaceMapper.map(element("amenity", "place_of_worship", "religion", "muslim",
                "name", "Rahmiye Kuran Kursu"))).isNull();
        assertThat(OsmPlaceMapper.map(element("amenity", "place_of_worship", "religion", "muslim",
                "name", "Pehlivanlar Mezarlığı"))).isNull();
    }

    @Test
    void municipalMarketIsAMarketNotASight() {
        OsmPlaceMapper.OsmPlace market = OsmPlaceMapper.map(element("amenity", "marketplace",
                "name", "Adem Yavuz Kapalı Pazar Yeri"));
        assertThat(market.category()).isEqualTo(PlaceCategory.MARKET);

        assertThat(OsmPlaceMapper.map(element("amenity", "marketplace", "name", "Kapalıçarşı")).category())
                .isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(OsmPlaceMapper.map(element("amenity", "marketplace", "name", "Tarihi Pazar",
                "historic", "yes")).category()).isEqualTo(PlaceCategory.ATTRACTION);
        // Rows of earlier imports
        assertThat(PlaceRealismFilter.rejectName("Orduyeri Mahallesi Semt Pazarı", PlaceCategory.ATTRACTION))
                .isNotNull();
    }

    @Test
    void otherBusinessesAreNoCafesRestaurantsOrMarkets() {
        for (String name : List.of("Ada Eczanesi", "Kudrret kuafor", "Nova Gayrimenkul", "Fidandibi Sitesi",
                "Yazı Köyü", "Hotel balin", "Meguiars Oto Yıkama", "Antalyalılar Derneği")) {
            assertThat(PlaceRealismFilter.rejectName(name, PlaceCategory.CAFE)).as(name).isNotNull();
        }
        for (String name : List.of("Akbank ATM", "Petrol Ofisi", "Anadolu Lisesi Karabağlar İzmir",
                "Hastane Sultangazi", "Gef İnşaat Malzemeleri", "İsabey Kurban Pazarı")) {
            assertThat(PlaceRealismFilter.rejectName(name, PlaceCategory.MARKET)).as(name).isNotNull();
        }
        // Food places named after where they are, lodging with a restaurant, chains
        for (String name : List.of("Lezzet Durağı", "Kampüs Midye", "Salda Gölü Venüs Restaurant Ve Pansiyon",
                "Petrol Fırın Cafe", "Çırpı Kahvaltı Köyü", "Espressolab Marmara Üniversitesi Dragos Kampüsü",
                "Aksa Cami Kıraathanesi", "Trabzon Vakfıkebir Ekmeği", "Doyran Koyu Osmanın Yeri", "Kampuscafe02",
                "Gülnar Oteli Mersin Gastronomi Konağı", "Güzelöz resturant ve pansiyon", "Meşhur Koyuncu Aspava",
                "Hacılar Et Galerisi", "Art Smyrna Cafe & Galeri", "Clup Hotel Nena Green Bar")) {
            assertThat(PlaceRealismFilter.rejectName(name, PlaceCategory.RESTAURANT)).as(name).isNull();
        }
        assertThat(PlaceRealismFilter.rejectName("Düzpaş - Hastane Şubesi", PlaceCategory.MARKET)).isNull();
        assertThat(PlaceRealismFilter.rejectName("BİM Çayırova", PlaceCategory.MARKET)).isNull();
    }

    private static OvertureMapper.OverturePlace overtureWorship(String name) {
        return OvertureMapper.map(new OvertureMapper.OvertureRow("o-" + name.hashCode(), name,
                List.of("religious_organization", "place_of_worship", "christian_place_of_worship"), 0.9, null,
                null, null, 40.76, 29.92));
    }

    private static OverpassResponse.Element element(String... keyValues) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            tags.put(keyValues[i], keyValues[i + 1]);
        }
        return new OverpassResponse.Element("node", 1, 40.76, 29.92, null, tags, null, null, null);
    }
}

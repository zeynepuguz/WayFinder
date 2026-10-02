package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.overture.OvertureMapper.OverturePlace;
import com.nomi.wayfinder.overture.OvertureMapper.OvertureRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

// Sights from Overture, with real Kocaeli rows: Meta pages are filed loosely, so names must say what they are
class OvertureSightsTest {

    @Test
    void museumsCultureVenuesHistoricSitesAndParks() {
        OverturePlace museum = map("Alifuatpaşa Kuvay-ı Milliye Müzesi", "arts_and_entertainment/museum/history_museum", 0.9);
        assertThat(museum.category()).isEqualTo(PlaceCategory.MUSEUM);
        assertThat(museum.indoor()).isTrue();

        assertThat(map("Kocaeli Arkeoloji Ve Etnografya Muzesi", "arts_and_entertainment/museum", 0.9).category())
                .isEqualTo(PlaceCategory.MUSEUM);
        assertThat(map("Süleyman Demirel Kültür Merkezi", "cultural_and_historic/cultural_center", 0.8).category())
                .isEqualTo(PlaceCategory.CULTURE);

        OverturePlace bridge = map("Tarihi Taş Köprü", "cultural_and_historic/memorial_site/monument", 0.8);
        assertThat(bridge.category()).isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(bridge.indoor()).isFalse();
        assertThat(bridge.tags()).contains("history");

        assertThat(map("Eriklitepe Tabiat Parkı", "sports_and_recreation/park/national_park", 0.9).category())
                .isEqualTo(PlaceCategory.PARK);
        assertThat(map("Kumcağız Plajı", "geographic_entities/land_feature/beach", 0.9).tags()).contains("sea");
    }

    @Test
    void misfiledOrUnsurePagesAreLeftOut() {
        // A housing estate filed as a beach, a mosque as a castle, a tunnel as a historic site
        assertThat(map("Ünal Sahil Sitesi", "geographic_entities/land_feature/beach", 0.9)).isNull();
        assertThat(map("Yeşil İrfaniye Camii", "cultural_and_historic/historic_site/castle", 0.9)).isNull();
        assertThat(map("Samanlı Tüneli", "cultural_and_historic/historic_site", 0.9)).isNull();
        assertThat(map("Yalova Ciftlikkoy Sultaniye", "sports_and_recreation/park", 0.9)).isNull();
        // Real Adana rows: housing estates, directorates and shops filed as parks / galleries / historic sites
        assertThat(map("Elit Park Evleri", "sports_and_recreation/park", 0.9)).isNull();
        assertThat(map("Nesli-Han Apartmani", "cultural_and_historic/historic_site", 0.9)).isNull();
        assertThat(map("Green City Begonya Towers", "cultural_and_historic/historic_site", 0.9)).isNull();
        assertThat(map("Ceyhan Park Ve Bahçeler Müdürlüğü", "sports_and_recreation/park", 0.9)).isNull();
        assertThat(map("Sel Auto Galeri", "arts_and_entertainment/arts_and_crafts_space/art_gallery", 0.9)).isNull();
        assertThat(map("Göktaş Mutfak Banyo Kapı", "arts_and_entertainment/arts_and_crafts_space/art_gallery", 0.9)).isNull();
        // Filed as a museum / culture venue but a restaurant or an association
        assertThat(map("Umut gözleme ve mantı evi", "arts_and_entertainment/museum", 0.9)).isNull();
        assertThat(map("Uzuntarla Kafkas Kültür Derneği", "cultural_and_historic/cultural_center", 0.9)).isNull();
        assertThat(map("Paşa Konakları", "cultural_and_historic/historic_site", 0.9)).isNull();
        assertThat(map("Avrupa Tır Yıkama Parkı", "sports_and_recreation/park", 0.9)).isNull();
        // ... but real ones stay
        assertThat(map("Kaiser Wilhelm Köşkü", "arts_and_entertainment/museum", 0.9).category())
                .isEqualTo(PlaceCategory.ATTRACTION);
        assertThat(map("Gökvelioğlu Kalesi", "cultural_and_historic/historic_site/castle", 0.9)).isNotNull();
        assertThat(map("Barış Manço Parkı", "sports_and_recreation/park", 0.9)).isNotNull();
        // Sights need 0.7 (food places 0.5)
        assertThat(map("Ormanya Parkı", "sports_and_recreation/park", 0.6)).isNull();
    }

    private static OverturePlace map(String name, String hierarchy, double confidence) {
        return OvertureMapper.map(new OvertureRow("o-" + name.hashCode(), name, Arrays.asList(hierarchy.split("/")),
                confidence, null, null, null, 40.76, 29.92));
    }
}

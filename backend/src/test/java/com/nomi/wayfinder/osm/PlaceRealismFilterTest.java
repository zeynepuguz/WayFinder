package com.nomi.wayfinder.osm;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.nomi.wayfinder.entity.PlaceCategory.*;
import static org.assertj.core.api.Assertions.assertThat;

// Real names from the Istanbul / Ankara / İzmir OSM imports
class PlaceRealismFilterTest {

    @Test
    void institutionCanteensAndDiningHallsAreNotPlacesToVisit() {
        assertThat(PlaceRealismFilter.rejectName("Merkez kantin", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("76. Yurt Kantini", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Tıp Fakültesi kantini", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Hastane Kantini", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Bilkent Üniversitesi Merkez Kampüs Yemekhanesi", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("YTÜ Yemekhanesi", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Fen Edebiyat Yemekhanesi", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Mühendislik Fak. Kantin-Kafe", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Black Cafe (Kantin)", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Yabancı Diller Yüksekokulu Kantini", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Saray İlkokulu", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Personel Lokantası", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Öğrenci Yurdu Kafeteryası", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Harbiye Orduevi", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Askeri Gazino", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("İstanbul Emniyet Müdürlüğü Polis Evi Ortaköy Sosyal Tesisleri", BREAKFAST))
                .isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Jandarma Kafeterya", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Öğretmen Lojmanları Kafe", CAFE)).isNotNull();
    }

    @Test
    void schoolGroundsAreNotParks() {
        assertThat(PlaceRealismFilter.rejectName("Tuna Akademi İncek Anaokulu", PARK)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("fen lisesi parkı", PARK)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("Özel Esenler Hayat Hastanesi  Parkı", PARK)).isNotNull();
    }

    @Test
    void genericWordsAndLowercaseDescriptionsAreNotNames() {
        assertThat(PlaceRealismFilter.rejectName("Kafe", CAFE)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("CAFE", CAFE)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("Restoran", RESTAURANT)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("Park", PARK)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("Çocuk Parkı", PARK)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("Çay Bahçesi", CAFE)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("Büfe", RESTAURANT)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("Yemekhane", RESTAURANT)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("123", RESTAURANT)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("okul kantini", CAFE)).isNotNull();
        assertThat(PlaceRealismFilter.rejectName("çay ocağı", CAFE)).isEqualTo("generic name");
        assertThat(PlaceRealismFilter.rejectName("köşe kafe", CAFE)).isEqualTo("description, not a name");
    }

    @Test
    void realPublicPlacesAreKept() {
        // Parks named after police / gendarmerie officers, or in the "Yurt" neighbourhood, are public parks
        assertThat(PlaceRealismFilter.rejectName("Şehit Polis Fuat Bal Parkı", PARK)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Şehit Jandarma Komando Ahmet Kuş Parkı", PARK)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Polis Emeklileri Parkı", PARK)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Yurt Mahallesi 9 Nolu Çocuk Parkı", PARK)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Çocuk Hastanesi Önü Parkı", PARK)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Yeşilyurt Spor Parkı", PARK)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Şehit Serhat Yurttaş Parkı", PARK)).isNull();
        // Museums in former prisons / factories, an art space in a former school
        assertThat(PlaceRealismFilter.rejectName("Galata Rum Okulu", CULTURE)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Ulucanlar Cezaevi Müzesi", MUSEUM)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Yıldız Seramik Fabrikası", MUSEUM)).isNull();
        // Municipal social facilities are public cafés / restaurants
        assertThat(PlaceRealismFilter.rejectName("İBB Dragos Sosyal Tesisleri", RESTAURANT)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Fatih Belediyesi Topkapı Sosyal Tesisleri", CAFE)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Gözdağı Sosyal Tesisleri", RESTAURANT)).isNull();
        // Ordinary names that merely contain a flagged word's letters
        assertThat(PlaceRealismFilter.rejectName("Kahve Dünyası", CAFE)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Köfteci Yusuf", RESTAURANT)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Emniyet Lokantası", RESTAURANT)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Kolej Pastanesi", DESSERT)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Abdurrahman Tatlıcı Fabrika Satış Mağazası", DESSERT)).isNull();
        assertThat(PlaceRealismFilter.rejectName("Moda Çay Bahçesi", CAFE)).isNull();
        // Stylised lowercase brand names that do not describe a kind of place
        assertThat(PlaceRealismFilter.rejectName("brew mood", CAFE)).isNull();
    }

    @Test
    void privateClosedAndDisusedElementsAreDropped() {
        assertThat(reject(Map.of("amenity", "cafe", "name", "Site Kafe", "access", "private"))).isEqualTo("access=private");
        assertThat(reject(Map.of("leisure", "park", "name", "Villa Bahçesi Parkı", "access", "no"))).isEqualTo("access=no");
        assertThat(reject(Map.of("amenity", "restaurant", "name", "Eski Lezzet", "disused", "yes"))).isEqualTo("disused");
        assertThat(reject(Map.of("amenity", "cafe", "name", "Kapanan Kahveci", "opening_hours", "off")))
                .isEqualTo("opening_hours=off");
        assertThat(reject(Map.of("amenity", "cafe", "name", "Moda Kahvecisi", "disused:amenity", "cafe")))
                .isEqualTo("disused:amenity");
        // A former restaurant that is a café now is open
        assertThat(reject(Map.of("amenity", "cafe", "name", "Moda Kahvecisi", "was:amenity", "restaurant"))).isNull();
        // Customers-only is how shops are tagged, not a private place
        assertThat(reject(Map.of("amenity", "cafe", "name", "Moda Kahvecisi", "access", "customers"))).isNull();
    }

    @Test
    void publicBusinessesKeepGenericOrInstitutionNames() {
        // The restaurant "Kantin" in Nişantaşı: a real business with a website and opening hours
        assertThat(reject(Map.of("amenity", "restaurant", "name", "Kantin", "website", "https://kantin.biz",
                "opening_hours", "Mo-Sa 12:00-22:00"))).isNull();
        assertThat(reject(Map.of("amenity", "cafe", "name", "Kafe", "phone", "+90 212 000 00 00"))).isNull();
        assertThat(reject(Map.of("amenity", "cafe", "name", "Kantin", "contact:website", "https://example.com"))).isNull();
        assertThat(reject(Map.of("amenity", "restaurant", "name", "Yemekhane", "brand", "Yemekhane"))).isNull();
        assertThat(reject(Map.of("tourism", "museum", "name", "Müze", "wikidata", "Q42"))).isNull();
        // Without any of these the name rules apply
        assertThat(reject(Map.of("amenity", "cafe", "name", "okul kantini"))).isNotNull();
        assertThat(reject(Map.of("amenity", "restaurant", "name", "Kantin"))).isEqualTo("generic name");
        assertThat(reject(Map.of("amenity", "cafe", "name", "Hastane Kantini"))).isNotNull();
        assertThat(reject(Map.of("amenity", "restaurant", "name", "Harbiye Orduevi"))).isNotNull();
        // Closed stays closed, whatever else it has
        assertThat(reject(Map.of("amenity", "cafe", "name", "Kantin", "website", "https://x.com", "opening_hours", "off")))
                .isEqualTo("opening_hours=off");
        assertThat(reject(Map.of("amenity", "cafe", "name", "Kantin", "website", "https://x.com", "access", "private")))
                .isEqualTo("access=private");
    }

    private static String reject(Map<String, String> tags) {
        OverpassResponse.Element element = new OverpassResponse.Element("node", 1, 40.99, 29.02, null, new HashMap<>(tags));
        OsmPlaceMapper.OsmPlace place = OsmPlaceMapper.map(element);
        assertThat(place).as("mapped %s", tags).isNotNull();
        return PlaceRealismFilter.rejectElement(element, place);
    }

    @Test
    void theImportSkipsUnrealisticElementsAndReportsThem() {
        OsmPlaceImporter.Prepared prepared = OsmPlaceImporter.prepare(java.util.List.of(
                        new OverpassResponse.Element("node", 1, 40.99, 29.02, null,
                                Map.of("amenity", "cafe", "name", "Merkez kantin")),
                        new OverpassResponse.Element("node", 2, 40.99, 29.03, null,
                                Map.of("amenity", "cafe", "name", "Moda Kahvecisi"))),
                new OsmDeduplicator(java.util.List.of()));
        assertThat(prepared.places()).extracting(OsmPlaceMapper.OsmPlace::osmId).containsExactly("node/2");
        assertThat(prepared.unrealisticIds()).containsExactly("node/1");
        assertThat(prepared.unrealisticExamples()).containsExactly("Merkez kantin (institution (kantin))");
    }
}

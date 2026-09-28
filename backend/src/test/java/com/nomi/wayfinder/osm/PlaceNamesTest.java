package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.entity.PlaceCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Names as shown to users: name:tr first, broken casing repaired, event descriptions dropped (real names from the imports)
class PlaceNamesTest {

    @Test
    void allLowercaseNamesGetTurkishTitleCase() {
        assertThat(PlaceNames.clean("köfteci yusuf")).isEqualTo("Köfteci Yusuf");
        assertThat(PlaceNames.clean("izmirli kıraathanesi")).isEqualTo("İzmirli Kıraathanesi");
        assertThat(PlaceNames.clean("dönerci ömer usta")).isEqualTo("Dönerci Ömer Usta");
        // Particles stay lowercase, apostrophe suffixes too
        assertThat(PlaceNames.clean("ali baba ve kardeşleri")).isEqualTo("Ali Baba ve Kardeşleri");
        assertThat(PlaceNames.clean("nuri'nin yeri")).isEqualTo("Nuri'nin Yeri");
        assertThat(PlaceNames.clean("sec-tart")).isEqualTo("Sec-Tart");
    }

    @Test
    void allCapsNamesLongerThanFourLettersGetTitleCase() {
        assertThat(PlaceNames.clean("KARDEŞLER LOKANTASI")).isEqualTo("Kardeşler Lokantası");
        assertThat(PlaceNames.clean("İMPARATOR KOKOREÇ")).isEqualTo("İmparator Kokoreç");
        assertThat(PlaceNames.clean("TRİLEÇE DÜNYASI")).isEqualTo("Trileçe Dünyası");
        assertThat(PlaceNames.clean("BALIKÇI SİNAN")).isEqualTo("Balıkçı Sinan");
        assertThat(PlaceNames.clean("ANNANİN YERİ")).isEqualTo("Annanin Yeri");
        // Short acronyms keep their capitals
        assertThat(PlaceNames.clean("İBB SOSYAL TESİSLERİ")).isEqualTo("İBB Sosyal Tesisleri");
        assertThat(PlaceNames.clean("KÖFTECİ VE KEBAPÇI")).isEqualTo("Köfteci ve Kebapçı");
    }

    @Test
    void brandStylingAndAmbiguousCapitalsAreKept() {
        // Up to 4 letters: a brand ("MADO", "KFC")
        assertThat(PlaceNames.clean("MADO")).isEqualTo("MADO");
        // Mixed case is the owner's own styling
        assertThat(PlaceNames.clean("KahveDünyası")).isEqualTo("KahveDünyası");
        assertThat(PlaceNames.clean("Van KAHVALTI Evi")).isEqualTo("Van KAHVALTI Evi");
        // "I" without "İ" or Turkish letters: English or Turkish without Turkish capitals - no guessing
        assertThat(PlaceNames.clean("BIG CHEFS")).isEqualTo("BIG CHEFS");
        assertThat(PlaceNames.clean("ISTANBUL'74")).isEqualTo("ISTANBUL'74");
    }

    @Test
    void whitespaceAndSurroundingQuotesAreCleaned() {
        assertThat(PlaceNames.clean("  Moda   Çay  Bahçesi ")).isEqualTo("Moda Çay Bahçesi");
        assertThat(PlaceNames.clean("\"Kahve Durağı\"")).isEqualTo("Kahve Durağı");
        assertThat(PlaceNames.clean("“Çınaraltı”")).isEqualTo("Çınaraltı");
        assertThat(PlaceNames.clean("   ")).isNull();
        assertThat(PlaceNames.clean(null)).isNull();
    }

    @Test
    void turkishNameFirstAndEnglishNameOnlyWhenItDiffers() {
        assertThat(PlaceNames.choose("Hagia Sophia", "Ayasofya")).isEqualTo("Ayasofya");
        assertThat(PlaceNames.choose("Kız Kulesi", null)).isEqualTo("Kız Kulesi");
        assertThat(PlaceNames.english("Maiden's Tower", "Kız Kulesi")).isEqualTo("Maiden's Tower");
        assertThat(PlaceNames.english("KIZ KULESİ", "Kız Kulesi")).isNull();
        assertThat(PlaceNames.english(null, "Kız Kulesi")).isNull();
    }

    @Test
    void eventDescriptionsAndSentencesAreNotPlaces() {
        assertThat(PlaceNames.describesEventOrSentence("Gezi Parkı olaylarının gerçekleştiği yer")).isTrue();
        assertThat(PlaceNames.describesEventOrSentence("Eski caminin bulunduğu yer")).isTrue();
        assertThat(PlaceNames.describesEventOrSentence(
                "Кемпинг с завтраком все включено в цену, вода, овощи яйца, чай и вся посуда. Есть душ и туалет.")).isTrue();

        assertThat(PlaceNames.describesEventOrSentence("Gezi Parkı")).isFalse();
        assertThat(PlaceNames.describesEventOrSentence("Atatürk'ün Doğduğu Ev")).isFalse();
        assertThat(PlaceNames.describesEventOrSentence(
                "Ulusal Bağımsızlık ve Kuruluş Müzesi ve Atatürk Devrimleri Müzesi")).isFalse();
        assertThat(PlaceNames.describesEventOrSentence(
                "Şehit Piyade Er Muhammet Osman Akagündüz Çocuk ve Dinlenme Parkı")).isFalse();
    }

    @Test
    void theImportDropsEventDescriptionsAndShowsCleanNames() {
        OverpassResponse.Element marker = new OverpassResponse.Element("node", 1, 41.037, 28.986, null,
                Map.of("historic", "memorial", "name", "Gezi Parkı olaylarının gerçekleştiği yer",
                        "wikidata", "Q13410316"));
        OsmPlaceMapper.OsmPlace place = OsmPlaceMapper.map(marker);
        assertThat(PlaceRealismFilter.rejectElement(marker, place)).isEqualTo("describes an event / a sentence");

        OverpassResponse.Element lowercase = new OverpassResponse.Element("node", 2, 41.0, 29.0, null,
                Map.of("amenity", "restaurant", "name", "köfteci yusuf", "name:en", "Kofteci Yusuf Restaurant",
                        "cuisine", "turkish;kebab"));
        OsmPlaceMapper.OsmPlace mapped = OsmPlaceMapper.map(lowercase);
        assertThat(mapped.name()).isEqualTo("Köfteci Yusuf");
        assertThat(mapped.nameEn()).isEqualTo("Kofteci Yusuf Restaurant");
        assertThat(mapped.cuisine()).isEqualTo("turkish;kebab");
        assertThat(mapped.tags()).contains("local");
        assertThat(PlaceRealismFilter.rejectElement(lowercase, mapped)).isNull();

        // A lowercase description is still recognized on the raw OSM name, before its casing is repaired
        OverpassResponse.Element canteen = new OverpassResponse.Element("node", 3, 41.0, 29.0, null,
                Map.of("amenity", "cafe", "name", "okul kantini"));
        assertThat(PlaceRealismFilter.rejectElement(canteen, OsmPlaceMapper.map(canteen))).isNotNull();
    }

    @Test
    void normalizerPlansRenamesTagsAndRemovals() {
        PlaceDataNormalizer.Plan plan = PlaceDataNormalizer.plan(java.util.List.of(
                new PlaceDataNormalizer.Row(1, "node/1", "köfteci yusuf", PlaceCategory.RESTAURANT,
                        java.util.List.of(), null, false),
                new PlaceDataNormalizer.Row(2, "node/2", "Gezi Parkı olaylarının gerçekleştiği yer",
                        PlaceCategory.ATTRACTION, java.util.List.of("history"), null, true),
                new PlaceDataNormalizer.Row(3, "node/3", "Moda Çay Bahçesi", PlaceCategory.CAFE,
                        java.util.List.of("tea"), null, false),
                new PlaceDataNormalizer.Row(4, "node/4", "okul kantini", PlaceCategory.CAFE,
                        java.util.List.of(), null, false),
                new PlaceDataNormalizer.Row(5, "node/5", "Kahve Durağı", PlaceCategory.CAFE,
                        java.util.List.of("coffee"), null, true)));

        assertThat(plan.notPlaces()).containsExactly("node/2", "node/4");
        assertThat(plan.renameExamples()).containsExactly("köfteci yusuf -> Köfteci Yusuf");
        assertThat(plan.updates()).extracting(PlaceDataNormalizer.Update::id).containsExactly(1L, 3L);
        assertThat(plan.updates().get(0).tags()).containsExactly("local");
        assertThat(plan.updates().get(1).tags()).containsExactly("tea", "local", "budget");
    }
}

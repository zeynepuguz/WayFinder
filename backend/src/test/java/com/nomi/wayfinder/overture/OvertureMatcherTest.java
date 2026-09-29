package com.nomi.wayfinder.overture;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.overture.OvertureMapper.OverturePlace;
import com.nomi.wayfinder.overture.OvertureMatcher.ExistingPlace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OvertureMatcherTest {

    private static final double LAT = 40.8161;
    private static final double LON = 29.3756;
    // ~0.0009 degrees of latitude = 100 m
    private static final double M100 = 0.0009;

    @Test
    void anOverturePlaceWithTheSameNameNearbyConfirmsTheOsmPlace() {
        ExistingPlace osm = new ExistingPlace(1, "Halisbey Et Lokantası", LAT, LON, "OSM");
        OverturePlace overture = place("o1", "HalİSbey Et Lokantasi", LAT + M100 * 0.5, LON, 0.75);

        OvertureMatcher.Result result = OvertureMatcher.match(List.of(overture), List.of(osm), 0.7);

        assertThat(result.confirmed()).containsEntry(1L, overture);
        assertThat(result.added()).isEmpty();
    }

    @Test
    void genericWordsDoNotMatterButTheNameDoes() {
        ExistingPlace kelebek = new ExistingPlace(1, "Kelebek Cafe", LAT, LON, "OSM");
        ExistingPlace neset = new ExistingPlace(2, "Neşetbey Et Lokantası", LAT, LON + 0.0005, "OSM");

        OvertureMatcher.Result result = OvertureMatcher.match(List.of(
                place("o1", "Kelebek Kafe ve Restaurant", LAT, LON, 0.9),
                place("o2", "Emre Et", LAT, LON + 0.0005, 0.94)), List.of(kelebek, neset), 0.7);

        assertThat(result.confirmed()).containsOnlyKeys(1L);
        // "Emre Et" is another restaurant, not Neşetbey: added as new, Neşetbey stays unconfirmed
        assertThat(result.added()).extracting(OverturePlace::name).containsExactly("Emre Et");
    }

    @Test
    void theSameNameFarAwayIsAnotherPlace() {
        ExistingPlace osm = new ExistingPlace(1, "Simit Sarayı", LAT, LON, "OSM");

        OvertureMatcher.Result result = OvertureMatcher.match(
                List.of(place("o1", "Simit Sarayı", LAT + M100 * 3, LON, 0.95)), List.of(osm), 0.7);

        assertThat(result.confirmed()).isEmpty();
        assertThat(result.added()).hasSize(1);
    }

    @Test
    void uncertainPlacesConfirmButAreNotAdded() {
        ExistingPlace osm = new ExistingPlace(1, "Kuytu", LAT, LON, "OSM");

        OvertureMatcher.Result result = OvertureMatcher.match(List.of(
                place("o1", "Kuytu", LAT, LON, 0.43),
                place("o2", "Glows Waffle", LAT, LON + 0.001, 0.24)), List.of(osm), 0.7);

        assertThat(result.confirmed()).containsOnlyKeys(1L);
        assertThat(result.added()).isEmpty();
    }

    @Test
    void twoPagesOfOnePlaceAreAddedOnceTheMoreCertainOne() {
        OvertureMatcher.Result result = OvertureMatcher.match(List.of(
                place("low", "Saklıköy Mantı Evi", LAT, LON, 0.8),
                place("high", "Saklıköy Mantı", LAT + M100 * 0.3, LON, 0.94),
                place("other", "Has Dürüm", LAT, LON, 0.97)), List.of(), 0.7);

        assertThat(result.added()).extracting(OverturePlace::overtureId).containsExactly("other", "high");
    }

    @Test
    void aSharedDistinctWordOrOneTypoIsTheSamePlace() {
        assertThat(OvertureMatcher.sharesToken("Zeze Dondurm Çiğ köfte Kafe",
                "Zeze Cig Kofte Dondurma Pilav Cafe Mutlukent")).isTrue();
        assertThat(OvertureMatcher.sharesToken("Izgara Izgara", "Izzgara Izzgara")).isTrue();
        // Only generic food words in common: two different places
        assertThat(OvertureMatcher.sharesToken("Tavuk Dünyası Gebze", "Tavukçu Ali Usta")).isFalse();
        assertThat(OvertureMatcher.sharesToken("Halil Usta'nın Yeri", "Özcan Gayrimenkul")).isFalse();
        assertThat(OvertureMatcher.containsFullName("Ali Ustanın Yeri", "Çorbacı Ali Ustanın Yeri")).isTrue();
        assertThat(OvertureMatcher.containsFullName("Et Evi", "Ahmet Et Evi")).isFalse();
        assertThat(OvertureMatcher.oneEditApart("kelebek", "kelebe")).isTrue();
        assertThat(OvertureMatcher.oneEditApart("kelebek", "kalabek")).isFalse();
    }

    @Test
    void everyNameRuleNeedsTheDistance() {
        ExistingPlace osm = new ExistingPlace(1, "Zeze Kafe", LAT, LON, "OSM");

        // Shares "zeze" but lies ~300 m away: another branch
        OvertureMatcher.Result result = OvertureMatcher.match(
                List.of(place("o1", "Zeze Çiğ Köfte Dondurma", LAT + M100 * 3, LON, 0.9)), List.of(osm), 0.7);

        assertThat(result.confirmed()).isEmpty();
        assertThat(result.added()).hasSize(1);
    }

    @Test
    void anotherPageWithTheSamePhoneIsTheSameBusiness() {
        ExistingPlace osm = new ExistingPlace(1, "Ali Ustanın Yeri", LAT, LON, "OSM");
        OverturePlace confirming = new OverturePlace("o1", "Çorbacı Ali Ustanın Yeri", PlaceCategory.RESTAURANT,
                List.of(), true, 0.88, "+90 541 586 80 09", null, LAT, LON + 0.0003);
        OverturePlace samePhone = new OverturePlace("o2", "05 Ali Usta İşkembe Kellepaça", PlaceCategory.RESTAURANT,
                List.of(), true, 0.92, "05415868009", null, LAT, LON + 0.0005);

        OvertureMatcher.Result result = OvertureMatcher.match(List.of(samePhone, confirming), List.of(osm), 0.7);

        assertThat(result.confirmed()).containsEntry(1L, confirming);
        assertThat(result.added()).isEmpty();
        assertThat(OvertureMatcher.phoneKey("+90 (262) 742 41 04")).isEqualTo("2627424104");
        assertThat(OvertureMatcher.phoneKey("112")).isNull();
    }

    @Test
    void aMarketNeverConfirmsTheCafeOfTheSameName() {
        ExistingPlace cafe = new ExistingPlace(1, "Yıldız Kafe", LAT, LON, "OSM", PlaceCategory.CAFE);
        OverturePlace market = new OverturePlace("o1", "Yıldız Market", PlaceCategory.MARKET, List.of(), true, 0.9,
                null, null, LAT, LON);

        OvertureMatcher.Result result = OvertureMatcher.match(List.of(market), List.of(cafe), 0.7);

        assertThat(result.confirmed()).isEmpty();
        assertThat(result.added()).containsExactly(market);
        // A famous mosque (a sight) is the same place as Overture's place of worship
        assertThat(OvertureMatcher.compatible(PlaceCategory.WORSHIP, PlaceCategory.ATTRACTION)).isTrue();
    }

    @Test
    void shortNamesOnlyMatchExactly() {
        assertThat(OvertureMatcher.sameName("ali", "aliusta")).isFalse();
        assertThat(OvertureMatcher.sameName("aliusta", "corbacialiustaninyeri")).isTrue();
        assertThat(OvertureMatcher.coreName("Cafe & Restaurant")).isEqualTo("caferestaurant");
    }

    @Test
    void readsTheLatestReleaseFromTheCatalog() {
        JsonMapper json = JsonMapper.builder().build();

        assertThat(OvertureClient.parseLatest("{\"type\":\"Catalog\",\"latest\":\"2026-09-23.1\"}", json))
                .isEqualTo("2026-09-23.1");
        assertThatThrownBy(() -> OvertureClient.parseLatest("{\"latest\":\"../../etc\"}", json))
                .isInstanceOf(IllegalStateException.class);
    }

    private static OverturePlace place(String id, String name, double lat, double lon, double confidence) {
        return new OverturePlace(id, name, PlaceCategory.RESTAURANT, List.of(), true, confidence, null, null, lat, lon);
    }
}

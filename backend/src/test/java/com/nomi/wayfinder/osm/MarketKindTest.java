package com.nomi.wayfinder.osm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Explore > Market > BİM / A101 / ŞOK / Migros / Hakmar / Bakkal ve diğer
class MarketKindTest {

    @Test
    void chainsByNameEverythingElseIndependent() {
        assertThat(PlaceTags.marketKind("BİM Çayırova")).isEqualTo("bim");
        assertThat(PlaceTags.marketKind("A-101")).isEqualTo("a101");
        assertThat(PlaceTags.marketKind("A101 Yeni Bağdat Gebze")).isEqualTo("a101");
        assertThat(PlaceTags.marketKind("Şok Mini")).isEqualTo("sok");
        assertThat(PlaceTags.marketKind("MMM Migros")).isEqualTo("migros");
        assertThat(PlaceTags.marketKind("Mimar Sinan Hakmar")).isEqualTo("hakmar");
        // Not chains: a street name, "Bimbo", a bakkal
        assertThat(PlaceTags.marketKind("Sokak Bakkalı")).isEqualTo("independent");
        assertThat(PlaceTags.marketKind("Bimbo Market")).isEqualTo("independent");
        assertThat(PlaceTags.marketKind("Sencan Süpermarket")).isEqualTo("independent");
    }
}

package com.nomi.wayfinder.osm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorshipKindTest {

    @Test
    void theNameDecidesThenTheReligion() {
        // Cami and mescit are one kind
        assertThat(PlaceTags.worshipKind("Adem Yavuz Elmas Camii", null)).isEqualTo("mosque");
        assertThat(PlaceTags.worshipKind("Simkaş Mescidi", null)).isEqualTo("mosque");
        assertThat(PlaceTags.worshipKind("Aya Yorgi Kilisesi", "muslim")).isEqualTo("church");
        assertThat(PlaceTags.worshipKind("Neve Şalom Sinagogu", null)).isEqualTo("synagogue");
        assertThat(PlaceTags.worshipKind("Karacaahmet Sultan Cemevi", "muslim")).isEqualTo("cemevi");
        assertThat(PlaceTags.worshipKind("Hacılar", "muslim")).isEqualTo("mosque");
        assertThat(PlaceTags.worshipKind("Surp Takavor", "christian")).isEqualTo("church");
        assertThat(PlaceTags.worshipKind("Bir Yer", null)).isNull();
    }
}

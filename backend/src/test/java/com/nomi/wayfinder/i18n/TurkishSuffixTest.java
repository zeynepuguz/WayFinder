package com.nomi.wayfinder.i18n;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurkishSuffixTest {

    @Test
    void genitiveFollowsVowelHarmony() {
        assertThat(TurkishSuffix.genitive("Bursa")).isEqualTo("Bursa'nın");
        assertThat(TurkishSuffix.genitive("İzmir")).isEqualTo("İzmir'in");
        assertThat(TurkishSuffix.genitive("Kadıköy")).isEqualTo("Kadıköy'ün");
        assertThat(TurkishSuffix.genitive("Üsküdar")).isEqualTo("Üsküdar'ın");
        assertThat(TurkishSuffix.genitive("Konya")).isEqualTo("Konya'nın");
        assertThat(TurkishSuffix.genitive("Ordu")).isEqualTo("Ordu'nun");
    }
}

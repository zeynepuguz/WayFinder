package com.nomi.wayfinder.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class SentencesTest {

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void everySentenceStartsWithACapitalLetter() {
        assertThat(Sentences.capitalize("istanbul hazır! rotan şöyle. iyi gezmeler?\nilk durak kahvaltı."))
                .isEqualTo("İstanbul hazır! Rotan şöyle. İyi gezmeler?\nİlk durak kahvaltı.");
        // Quotes in front of a sentence, times and numbers, decimals
        assertThat(Sentences.capitalize("\"fark etmez\" dersen başlarım.")).isEqualTo("\"Fark etmez\" dersen başlarım.");
        assertThat(Sentences.capitalize("09:30 → kahvaltı: Simitçi (kişi başı ~4.5 TL)"))
                .isEqualTo("09:30 → kahvaltı: Simitçi (kişi başı ~4.5 TL)");
    }
}

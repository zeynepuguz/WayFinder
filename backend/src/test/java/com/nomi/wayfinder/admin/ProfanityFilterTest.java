package com.nomi.wayfinder.admin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfanityFilterTest {

    @Test
    void swearWordsAndTheirDisguisesAreFound() {
        for (String text : List.of("amk bu ne", "Siktir git", "ORUSPU çocuğu", "s1kt1r", "o r o s p u",
                "siiiiktir", "yarrak", "amına koyayım", "şerefsizler", "pezevenk", "what the fuck", "aq")) {
            assertThat(ProfanityFilter.containsProfanity(text)).as(text).isTrue();
        }
    }

    @Test
    void ordinaryWordsThatLookAlikeAreFine() {
        for (String text : List.of("Uygulama çok güzel, harita sıkışık görünüyor", "Biraz sıkıntı yaşadım",
                "Rota önerileri yaramaz değil", "Amin abi teşekkürler", "Kafe önerileri daha fazla olsun",
                "Gece açık mekanlar eklenebilir mi?", "Sıkı bir iş çıkarmışsınız")) {
            assertThat(ProfanityFilter.containsProfanity(text)).as(text).isFalse();
        }
    }
}

package com.nomi.wayfinder.area;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// "... bize yarın sabah 10'dan başlayarak bir gezi rotası oluşturur musun" was read as the city of Muş
class QuestionParticleTest {

    private final AreaMatcher matcher = new AreaMatcher(List.of(
            new NamedArea("Muş", NamedArea.Kind.CITY, 38.74, 41.49, null, "Muş", "mus", null)));

    @Test
    void questionParticlesAreNoPlaces() {
        assertThat(matcher.find("merhaba, 2 kişiyiz 3000 TL'miz var bize yarın sabah 10dan başlayarak bir gezi "
                + "rotası oluşturur musun")).isEmpty();
        assertThat(matcher.find("rota önerir misiniz")).isEmpty();
        // The city itself is still found
        assertThat(matcher.find("Muş'ta bir gün geçireceğiz")).isPresent();
        assertThat(matcher.find("yarın Muşa gidiyoruz")).isPresent();
    }
}

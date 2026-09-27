package com.nomi.wayfinder.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BosphorusSidesTest {

    @Test
    void europeanNeighbourhoodsAreOnTheEuropeanSide() {
        assertThat(BosphorusSides.isAsianSide(41.0055, 28.9768)).isFalse(); // Sultanahmet
        assertThat(BosphorusSides.isAsianSide(41.0422, 29.0067)).isFalse(); // Beşiktaş
        assertThat(BosphorusSides.isAsianSide(41.0340, 28.9780)).isFalse(); // Taksim
        assertThat(BosphorusSides.isAsianSide(41.0770, 29.0440)).isFalse(); // Bebek
        assertThat(BosphorusSides.isAsianSide(41.1670, 29.0570)).isFalse(); // Sarıyer
    }

    @Test
    void asianNeighbourhoodsAreOnTheAsianSide() {
        assertThat(BosphorusSides.isAsianSide(40.9910, 29.0230)).isTrue(); // Kadıköy
        assertThat(BosphorusSides.isAsianSide(41.0260, 29.0150)).isTrue(); // Üsküdar
        assertThat(BosphorusSides.isAsianSide(41.0450, 29.0450)).isTrue(); // Beylerbeyi
        assertThat(BosphorusSides.isAsianSide(41.1350, 29.0930)).isTrue(); // Beykoz
    }

    @Test
    void sultanahmetAndKadikoyAreNotWalkable() {
        assertThat(BosphorusSides.sameSide(41.0055, 28.9768, 40.9910, 29.0230)).isFalse();
        assertThat(BosphorusSides.sameSide(40.9910, 29.0230, 40.9850, 29.0300)).isTrue();
    }
}

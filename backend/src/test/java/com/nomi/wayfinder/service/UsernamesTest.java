package com.nomi.wayfinder.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsernamesTest {

    @Test
    void namesBecomePlainLowercaseLetters() {
        assertThat(Usernames.plain("Şükrü Yılmaz")).isEqualTo("sukru");
        assertThat(Usernames.plain("İrem")).isEqualTo("irem");
        assertThat(Usernames.plain("Çağla-Nur")).isEqualTo("caglanur");
        assertThat(Usernames.plain(null)).isEmpty();
    }

    @Test
    void theRules() {
        assertThat(Usernames.isValid("zeynep_7")).isTrue();
        assertThat(Usernames.isValid("z.uguz")).isTrue();
        assertThat(Usernames.isValid("ab")).isFalse();
        assertThat(Usernames.isValid("7zeynep")).isFalse();
        assertThat(Usernames.isValid("zeynep.")).isFalse();
        assertThat(Usernames.isValid("zey..nep")).isFalse();
        assertThat(Usernames.isValid("zeynep-uguz")).isFalse();
        assertThat(Usernames.isValid("a".repeat(21))).isFalse();
        assertThat(Usernames.isValid("nomi")).isFalse();
        assertThat(Usernames.normalize(" Zeynep_7 ")).isEqualTo("zeynep_7");
        assertThat(Usernames.normalize("IŞIL")).isEqualTo("isil");
    }
}

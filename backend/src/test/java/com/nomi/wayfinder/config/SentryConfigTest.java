package com.nomi.wayfinder.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SentryConfigTest {

    @Test
    void emailAddressesNeverLeaveForSentry() {
        assertThat(SentryConfig.mask("Sign-in locked for 15 minutes after 5 wrong passwords: zeynep.u+test@gmail.com"))
                .isEqualTo("Sign-in locked for 15 minutes after 5 wrong passwords: [email]");
        assertThat(SentryConfig.mask("E-mail could not be sent: 550 no such user a@b.co.uk")).endsWith("[email]");
        assertThat(SentryConfig.mask("Photo 5 approved")).isEqualTo("Photo 5 approved");
        assertThat(SentryConfig.mask(null)).isNull();
    }
}

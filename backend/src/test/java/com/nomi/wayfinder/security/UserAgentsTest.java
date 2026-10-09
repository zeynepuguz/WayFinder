package com.nomi.wayfinder.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentsTest {

    @Test
    void namesBrowserAndSystem() {
        assertThat(UserAgents.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/141.0.0.0 Safari/537.36")).isEqualTo("Chrome · Windows");
        assertThat(UserAgents.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0")).isEqualTo("Edge · Windows");
        assertThat(UserAgents.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1")).isEqualTo("Safari · iOS");
        assertThat(UserAgents.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:131.0) Gecko/20100101 Firefox/131.0"))
                .isEqualTo("Firefox · macOS");
    }

    @Test
    void theAppsWebViewIsNomi() {
        assertThat(UserAgents.describe("Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP2A.240805.005; wv) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Version/4.0 Chrome/141.0.0.0 Mobile Safari/537.36")).isEqualTo("Nomi · Android");
        assertThat(UserAgents.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Mobile/15E148")).isEqualTo("Nomi · iOS");
    }

    @Test
    void unknownWhenItTellsNothing() {
        assertThat(UserAgents.describe(null)).isNull();
        assertThat(UserAgents.describe("curl/8.9.1")).isNull();
    }
}

package com.nomi.wayfinder.aiusage;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiUsageServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T09:00:00Z"), ZoneId.of("Europe/Istanbul"));

    @Test
    void costIsListPricePerMillionTokens() {
        AiUsageProperties prices = new AiUsageProperties(0.40, 1.60, 10);

        // 1,200 input + 80 output tokens of gpt-4.1-mini
        assertThat(prices.cost(1200, 80)).isCloseTo(0.000608, within(1e-9));
        assertThat(prices.cost(0, 0)).isZero();
    }

    @Test
    void recordsTokensAndCostFromTheAiServiceHeaders() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiUsageService service = new AiUsageService(jdbc, new AiUsageProperties(0.40, 1.60, 10), CLOCK);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-AI-Model", "gpt-4.1-mini");
        headers.add("X-AI-Input-Tokens", "1200");
        headers.add("X-AI-Output-Tokens", "80");

        service.record(AiUsageService.Kind.INTENT, 7L, headers, true, 950);

        verify(jdbc).update(contains("INSERT INTO ai_usage"), eq(7L), eq("INTENT"), eq("gpt-4.1-mini"), eq(1200), eq(80),
                doubleThat(cost -> Math.abs(cost - 0.000608) < 1e-9), eq(true), eq(950));
    }

    @Test
    void missingOrBrokenHeadersCountAsZeroTokens() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-AI-Input-Tokens", "abc");
        headers.add("X-AI-Output-Tokens", "-5");

        assertThat(AiUsageService.intHeader(headers, "X-AI-Input-Tokens")).isZero();
        assertThat(AiUsageService.intHeader(headers, "X-AI-Output-Tokens")).isZero();
        assertThat(AiUsageService.intHeader(headers, "X-AI-Model-Missing")).isZero();
    }

    @Test
    void recordingNeverFailsTheRequest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("db down"));
        AiUsageService service = new AiUsageService(jdbc, new AiUsageProperties(0.40, 1.60, 10), CLOCK);

        service.record(AiUsageService.Kind.PHOTO, null, new HttpHeaders(), false, 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void budgetIsReachedWhenThisMonthsCostIsAtTheLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new AiUsageService.Totals(500, 2, 9_000_000, 900_000, 10.2));

        assertThat(new AiUsageService(jdbc, new AiUsageProperties(0.40, 1.60, 10), CLOCK).budgetReached()).isTrue();
        assertThat(new AiUsageService(jdbc, new AiUsageProperties(0.40, 1.60, 20), CLOCK).budgetReached()).isFalse();
    }

    @Test
    void noBudgetLockWhenTheBudgetIsZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(new AiUsageService(jdbc, new AiUsageProperties(0.40, 1.60, 0), CLOCK).budgetReached()).isFalse();
        verifyNoInteractions(jdbc);
    }

    @Test
    void callsAreKindedByPath() {
        assertThat(AiUsageInterceptor.kind("/v1/intent")).isEqualTo(AiUsageService.Kind.INTENT);
        assertThat(AiUsageInterceptor.kind("/v1/photos/verify")).isEqualTo(AiUsageService.Kind.PHOTO);
        assertThat(AiUsageInterceptor.kind("/health")).isEqualTo(AiUsageService.Kind.OTHER);
    }
}

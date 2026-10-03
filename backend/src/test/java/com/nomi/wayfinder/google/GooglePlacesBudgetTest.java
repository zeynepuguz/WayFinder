package com.nomi.wayfinder.google;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

// Never a billed Google call: our own day / month share under Google's 5,000 free calls a month
class GooglePlacesBudgetTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T09:00:00Z"), ZoneId.of("Europe/Istanbul"));

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void withinTheDailyAndMonthlyShare() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(startsWith("google-places:month:2026-10"))).thenReturn(100L);
        when(values.increment(startsWith("google-places:day:2026-10-04"))).thenReturn(10L);

        assertThat(new GooglePlacesBudget(redis, CLOCK).tryAcquire()).isTrue();
    }

    @Test
    void overTheDailyOrMonthlyShareNothingIsAsked() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(startsWith("google-places:month:"))).thenReturn(100L);
        when(values.increment(startsWith("google-places:day:"))).thenReturn((long) GooglePlacesBudget.DAILY_LIMIT + 1);
        assertThat(new GooglePlacesBudget(redis, CLOCK).tryAcquire()).isFalse();

        when(values.increment(startsWith("google-places:month:"))).thenReturn((long) GooglePlacesBudget.MONTHLY_LIMIT + 1);
        when(values.increment(startsWith("google-places:day:"))).thenReturn(1L);
        assertThat(new GooglePlacesBudget(redis, CLOCK).tryAcquire()).isFalse();
    }

    @Test
    void whenRedisCannotCountGoogleIsNotAsked() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertThat(new GooglePlacesBudget(redis, CLOCK).tryAcquire()).isFalse();
    }
}

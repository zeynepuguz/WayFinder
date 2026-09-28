package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.*;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PhotoUploadLimiterTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final Map<String, Long> counters = new HashMap<>();
    private StringRedisTemplate redis;
    private PhotoUploadLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
        when(ops.get(anyString())).thenAnswer(inv -> {
            Long value = counters.get((String) inv.getArgument(0));
            return value == null ? null : value.toString();
        });
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 9, 28, 12, 0).atZone(ISTANBUL).toInstant(), ISTANBUL);
        limiter = new PhotoUploadLimiter(redis, clock,
                new PhotoProperties("x", "/media/photos", 10, 10, 3, Duration.ofSeconds(60)));
    }

    @Test
    void threePerTargetAndTenPerDay() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(1, PhotoTargetType.PLACE, 7);
        }
        assertThatThrownBy(() -> limiter.acquire(1, PhotoTargetType.PLACE, 7))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getMessage()).isEqualTo("Bu mekana bugün en fazla 3 fotoğraf yükleyebilirsin.");
                });

        for (long place = 100; place < 107; place++) {
            limiter.acquire(1, PhotoTargetType.PLACE, place);
        }
        assertThatThrownBy(() -> limiter.acquire(1, PhotoTargetType.DISTRICT, 3))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Bugün en fazla 10 fotoğraf yükleyebilirsin. Yarın tekrar dene."));

        // Other users are not affected
        limiter.acquire(2, PhotoTargetType.PLACE, 7);
    }

    @Test
    void redisOutageDoesNotBlockUploads() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis down"));

        limiter.acquire(1, PhotoTargetType.PLACE, 7);
    }
}

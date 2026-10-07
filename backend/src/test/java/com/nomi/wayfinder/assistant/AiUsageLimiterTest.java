package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.config.NomiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AiUsageLimiterTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final Map<String, Long> counters = new HashMap<>();
    private StringRedisTemplate redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
    }

    private AiUsageLimiter limiter(int dailyLimit, LocalDateTime istanbulTime) {
        Clock clock = Clock.fixed(istanbulTime.atZone(ISTANBUL).toInstant(), ISTANBUL);
        NomiProperties properties = new NomiProperties("Europe/Istanbul", null,
                null, new NomiProperties.Ai("http://ai", "key", null, null, dailyLimit), null, null, null, null, null);
        return new AiUsageLimiter(redis, clock, properties);
    }

    @Test
    void allowsUpToTheDailyLimitPerUser() {
        AiUsageLimiter limiter = limiter(3, LocalDateTime.of(2026, 9, 27, 12, 0));

        List<Boolean> results = List.of(limiter.tryAcquire("user:1"), limiter.tryAcquire("user:1"),
                limiter.tryAcquire("user:1"), limiter.tryAcquire("user:1"));

        assertThat(results).containsExactly(true, true, true, false);
        assertThat(limiter.tryAcquire("user:2")).isTrue();
    }

    @Test
    void quotaResetsOnTheNextIstanbulDay() {
        limiter(1, LocalDateTime.of(2026, 9, 27, 23, 59)).tryAcquire("user:1");
        assertThat(limiter(1, LocalDateTime.of(2026, 9, 27, 23, 59)).tryAcquire("user:1")).isFalse();

        assertThat(limiter(1, LocalDateTime.of(2026, 9, 28, 0, 1)).tryAcquire("user:1")).isTrue();
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("connection refused"));

        assertThat(limiter(1, LocalDateTime.of(2026, 9, 27, 12, 0)).tryAcquire("user:1")).isTrue();
    }

    @Test
    void overQuotaMessagesUseTheRuleBasedParser() {
        AiServiceIntentParser ai = mock(AiServiceIntentParser.class);
        RuleBasedIntentParser rules = mock(RuleBasedIntentParser.class);
        when(ai.isEnabled()).thenReturn(true);
        FallbackIntentParser parser = new FallbackIntentParser(ai, rules,
                limiter(1, LocalDateTime.of(2026, 9, 27, 12, 0)));
        IntentParser.IntentContext context = new IntentParser.IntentContext(false, List.of());

        parser.parse("kahve içmek istiyorum", context);
        parser.parse("yağmur başladı", context);

        verify(ai, times(1)).parse(anyString(), any());
        verify(rules).parse("yağmur başladı", context);
    }

    @Test
    void monthlyBudgetReachedUsesTheRuleBasedParser() {
        AiServiceIntentParser ai = mock(AiServiceIntentParser.class);
        RuleBasedIntentParser rules = mock(RuleBasedIntentParser.class);
        when(ai.isEnabled()).thenReturn(true);
        when(ai.budgetReached()).thenReturn(true);
        FallbackIntentParser parser = new FallbackIntentParser(ai, rules,
                limiter(150, LocalDateTime.of(2026, 9, 27, 12, 0)));
        IntentParser.IntentContext context = new IntentParser.IntentContext(false, List.of());

        parser.parse("kahve içmek istiyorum", context);

        verify(ai, never()).parse(anyString(), any());
        verify(rules).parse("kahve içmek istiyorum", context);
    }
}

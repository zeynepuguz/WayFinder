package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.config.NomiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Daily per-user quota for AI service calls (each one costs OpenAI money).
 * Counts in Redis per Istanbul calendar day. Over the quota the caller uses the rule-based parser,
 * so a user is never blocked, only served without the LLM. Fails open if Redis is down;
 * the OpenAI project budget limit is the hard backstop.
 */
@Component
public class AiUsageLimiter {

    private static final Logger log = LoggerFactory.getLogger(AiUsageLimiter.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int dailyLimit;

    public AiUsageLimiter(StringRedisTemplate redis, Clock clock, NomiProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.dailyLimit = properties.ai().dailyLimitPerUser();
    }

    /** Counts one AI call for the user; false when today's quota is already used up. */
    public boolean tryAcquire(String userKey) {
        if (dailyLimit <= 0) {
            return true;
        }
        String key = "ai-quota:" + userKey + ":" + LocalDate.now(clock);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofDays(2));
            }
            if (count != null && count > dailyLimit) {
                if (count == dailyLimit + 1) {
                    log.warn("AI daily quota reached for {}, using rule-based parser for the rest of the day", userKey);
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("AI quota check skipped, Redis unavailable: {}", e.getMessage());
            return true;
        }
    }
}

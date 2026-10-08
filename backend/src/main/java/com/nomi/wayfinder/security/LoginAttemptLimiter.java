package com.nomi.wayfinder.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Password guessing protection per account: after 5 wrong passwords within 15 minutes the e-mail is locked for the
 * rest of those 15 minutes (also for the right password), whichever IP the guesses come from. The per-IP request
 * limit (RateLimitInterceptor) comes on top. Fails open when Redis is down: the e-mailed code still protects.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public LoginAttemptLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean locked(String email) {
        try {
            String count = redis.opsForValue().get(key(email));
            return count != null && Long.parseLong(count) >= MAX_FAILURES;
        } catch (RuntimeException e) {
            log.warn("Login lock check skipped, Redis unavailable: {}", e.getMessage());
            return false;
        }
    }

    public void failed(String email) {
        try {
            Long count = redis.opsForValue().increment(key(email));
            if (count != null && count == 1) {
                redis.expire(key(email), WINDOW);
            }
            if (count != null && count == MAX_FAILURES) {
                log.warn("Sign-in locked for 15 minutes after {} wrong passwords: {}", MAX_FAILURES, email);
            }
        } catch (RuntimeException e) {
            log.warn("Failed sign-in not counted, Redis unavailable: {}", e.getMessage());
        }
    }

    public void succeeded(String email) {
        try {
            redis.delete(key(email));
        } catch (RuntimeException e) {
            // the counter just runs out by itself
        }
    }

    private static String key(String email) {
        return "login-fail:" + email.trim().toLowerCase(Locale.ROOT);
    }
}

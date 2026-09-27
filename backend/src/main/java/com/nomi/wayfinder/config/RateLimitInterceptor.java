package com.nomi.wayfinder.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;

/**
 * Fixed-window rate limit in Redis: N requests per user per minute on expensive endpoints
 * (route planning calls PostGIS + weather; later the assistant calls an LLM, which costs money).
 * If Redis is unavailable the request is allowed (fail-open) so users are not blocked by an outage.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final StringRedisTemplate redis;
    private final int limit;

    public RateLimitInterceptor(StringRedisTemplate redis, NomiProperties properties) {
        this.redis = redis;
        this.limit = properties.rateLimit().requestsPerMinute();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }

        long minute = Instant.now().getEpochSecond() / 60;
        String key = "rate:" + clientKey(request) + ":" + minute;

        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(60));
            }
        } catch (Exception e) {
            log.warn("Rate limit check skipped, Redis unavailable: {}", e.getMessage());
            return true;
        }

        if (count != null && count > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":429,\"message\":\"Too many requests, try again in a minute\",\"errors\":{}}");
            return false;
        }
        return true;
    }

    private static String clientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return "user:" + jwt.getToken().getSubject();
        }
        return "ip:" + request.getRemoteAddr();
    }
}

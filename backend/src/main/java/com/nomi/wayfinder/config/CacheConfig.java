package com.nomi.wayfinder.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

// If Redis is down, the cache is skipped (method runs normally) instead of failing the request
@Configuration
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);
    // Popular routes are full planner runs (up to 5 routes); imports, cleanups and popularity updates clear them
    static final Duration POPULAR_ROUTES_TTL = Duration.ofHours(3);

    // Per-cache time to live; other caches keep spring.cache.redis.time-to-live
    @Bean
    public RedisCacheManagerBuilderCustomizer popularRoutesCacheTtl() {
        // The app's class loader (like Spring Boot's own default): with the JVM default, cached DTOs could not
        // be cast back after a devtools restart ("Cannot cast X to X")
        return builder -> builder.withCacheConfiguration(com.nomi.wayfinder.service.PopularRouteService.CACHE,
                RedisCacheConfiguration.defaultCacheConfig(CacheConfig.class.getClassLoader()).entryTtl(POPULAR_ROUTES_TTL));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache get failed on {}: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache put failed on {}: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache evict failed on {}: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Cache clear failed on {}: {}", cache.getName(), e.getMessage());
            }
        };
    }
}

package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.i18n.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Upload quota per Istanbul calendar day, in Redis: N photos per user and M per user per place / district.
 * Every accepted upload counts (also ones rejected later): each one may cost an AI check. Deleting a photo
 * does not give the slot back. Fails open when Redis is down, like the other limiters.
 */
@Component
public class PhotoUploadLimiter {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadLimiter.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final PhotoProperties properties;

    public PhotoUploadLimiter(StringRedisTemplate redis, Clock clock, PhotoProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.properties = properties;
    }

    /** Counts one upload; 429 when the user's daily or per-target quota is used up. */
    public void acquire(long userId, PhotoTargetType type, long targetId) {
        LocalDate today = LocalDate.now(clock);
        String userKey = "photo-uploads:" + userId + ":" + today;
        String targetKey = "photo-uploads:" + userId + ":" + type.name() + ":" + targetId + ":" + today;
        try {
            if (count(userKey) >= properties.dailyUploadsPerUser()) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, Texts.t(
                        "Bugün en fazla " + properties.dailyUploadsPerUser() + " fotoğraf yükleyebilirsin. "
                                + "Yarın tekrar dene.",
                        "You can upload at most " + properties.dailyUploadsPerUser() + " photos a day. "
                                + "Try again tomorrow."));
            }
            if (count(targetKey) >= properties.dailyUploadsPerTarget()) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, type == PhotoTargetType.PLACE
                        ? Texts.t("Bu mekana bugün en fazla " + properties.dailyUploadsPerTarget()
                                + " fotoğraf yükleyebilirsin.",
                        "You can upload at most " + properties.dailyUploadsPerTarget()
                                + " photos of this place a day.")
                        : Texts.t("Bu bölgeye bugün en fazla " + properties.dailyUploadsPerTarget()
                                + " fotoğraf yükleyebilirsin.",
                        "You can upload at most " + properties.dailyUploadsPerTarget()
                                + " photos of this area a day."));
            }
            increment(userKey);
            increment(targetKey);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Photo upload quota check skipped, Redis unavailable: {}", e.getMessage());
        }
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private void increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofDays(2));
        }
    }
}

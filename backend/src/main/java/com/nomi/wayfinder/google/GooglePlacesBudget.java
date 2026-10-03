package com.nomi.wayfinder.google;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Our own lock on Google Places spending, next to the daily quota set in Google Cloud: at most DAILY_LIMIT calls a
 * day and MONTHLY_LIMIT a month, both under Google's free 5,000 Text Search Pro calls a month, so a call is never
 * billed. Counts in Redis per Istanbul day / month. Fails closed: when Redis cannot count, Google is not asked.
 */
@Component
public class GooglePlacesBudget {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesBudget.class);

    // 140 x 31 = 4,340 < 4,500 < 5,000 free a month (Google's own daily quota is 150)
    static final int DAILY_LIMIT = 140;
    static final int MONTHLY_LIMIT = 4500;

    private final StringRedisTemplate redis;
    private final Clock clock;

    public GooglePlacesBudget(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Counts one call; false (and nothing is asked) when today's or this month's share is used up. */
    public boolean tryAcquire() {
        LocalDate today = LocalDate.now(clock);
        String day = "google-places:day:" + today;
        String month = "google-places:month:" + YearMonth.from(today);
        try {
            Long monthCount = redis.opsForValue().increment(month);
            if (monthCount != null && monthCount == 1) {
                redis.expire(month, Duration.ofDays(40));
            }
            Long dayCount = redis.opsForValue().increment(day);
            if (dayCount != null && dayCount == 1) {
                redis.expire(day, Duration.ofDays(2));
            }
            if (monthCount == null || dayCount == null || monthCount > MONTHLY_LIMIT || dayCount > DAILY_LIMIT) {
                if (dayCount != null && dayCount == DAILY_LIMIT + 1 || monthCount != null && monthCount == MONTHLY_LIMIT + 1) {
                    log.warn("Google Places free share used up (day {}, month {}): no more checks until it resets",
                            dayCount, monthCount);
                }
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Google Places budget cannot be counted ({}): not asking Google", e.getMessage());
            return false;
        }
    }
}

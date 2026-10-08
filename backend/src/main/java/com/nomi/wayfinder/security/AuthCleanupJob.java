package com.nomi.wayfinder.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/**
 * Nightly: sign-ups whose code was never entered (after a day the address is free again), ended or expired
 * sessions, and used or expired e-mail codes.
 */
@Component
public class AuthCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuthCleanupJob.class);

    static final Duration UNVERIFIED_KEPT = Duration.ofDays(1);

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final Clock clock;

    public AuthCleanupJob(JdbcTemplate jdbc, SessionService sessions, Clock clock) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Scheduled(cron = "${nomi.security.cleanup-cron:0 30 4 * * *}", zone = "${nomi.timezone:Europe/Istanbul}")
    public void run() {
        try {
            Timestamp dayAgo = Timestamp.from(clock.instant().minus(UNVERIFIED_KEPT));
            int users = jdbc.update("DELETE FROM users WHERE email_verified_at IS NULL AND created_at < ?", dayAgo);
            int codes = jdbc.update("DELETE FROM email_codes WHERE expires_at < ? OR used_at IS NOT NULL", dayAgo);
            int ended = sessions.deleteOld();
            log.info("Auth cleanup: {} unverified sign-up(s), {} code(s), {} session(s) removed", users, codes, ended);
        } catch (Exception e) {
            log.warn("Auth cleanup failed, retried tomorrow: {}", e.getMessage());
        }
    }
}

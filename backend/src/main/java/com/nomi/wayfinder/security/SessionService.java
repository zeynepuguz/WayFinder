package com.nomi.wayfinder.security;

import com.nomi.wayfinder.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Signed-in devices. The app holds a short JWT (JWT_EXPIRATION_MINUTES) and a refresh token; with the refresh token
 * it gets a new pair, which moves the session's end 7 days ahead. So a user who opens the app at least once a week
 * stays signed in, one who does not signs in again.
 * <p>
 * Every refresh replaces the refresh token. The previous one is remembered: if it comes back later than a minute
 * (two tabs refreshing at the same moment), it was copied by someone else and the session is ended.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    public static final Duration IDLE_TIMEOUT = Duration.ofDays(7);
    // Two tabs / requests that refresh with the same token at nearly the same time are not an attack
    static final Duration REUSE_GRACE = Duration.ofMinutes(1);
    public static final String SIGN_IN_AGAIN = "Session expired, sign in again";

    public record Issued(long userId, String refreshToken, Instant expiresAt) {
    }

    private record Row(long id, long userId, Instant expiresAt, Instant revokedAt) {
    }

    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SessionService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Issued start(long userId, String userAgent) {
        Instant now = clock.instant();
        String token = newToken();
        Instant expiresAt = now.plus(IDLE_TIMEOUT);
        jdbc.update("""
                INSERT INTO user_sessions (user_id, token_hash, created_at, last_used_at, expires_at, user_agent)
                VALUES (?, ?, ?, ?, ?, ?)""",
                userId, hash(token), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt),
                userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())));
        return new Issued(userId, token, expiresAt);
    }

    /** A new refresh token for a valid one, 7 more days; 401 for anything else. */
    @Transactional(noRollbackFor = BusinessException.class)
    public Issued refresh(String token) {
        Instant now = clock.instant();
        String hash = hash(token);
        List<Row> current = jdbc.query("""
                SELECT id, user_id, expires_at, revoked_at FROM user_sessions WHERE token_hash = ? FOR UPDATE""",
                (rs, i) -> row(rs), hash);
        if (current.isEmpty()) {
            reuse(hash, now);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN);
        }
        Row session = current.getFirst();
        if (session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN);
        }
        String next = newToken();
        Instant expiresAt = now.plus(IDLE_TIMEOUT);
        jdbc.update("""
                UPDATE user_sessions SET token_hash = ?, previous_hash = ?, replaced_at = ?, last_used_at = ?,
                       expires_at = ? WHERE id = ?""",
                hash(next), hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt), session.id());
        return new Issued(session.userId(), next, expiresAt);
    }

    // An already replaced token came back: within the grace minute it is a parallel refresh, later a stolen copy
    private void reuse(String hash, Instant now) {
        jdbc.query("""
                SELECT id, user_id, replaced_at FROM user_sessions WHERE previous_hash = ? AND revoked_at IS NULL""",
                rs -> {
                    Instant replacedAt = rs.getTimestamp("replaced_at").toInstant();
                    if (replacedAt.plus(REUSE_GRACE).isBefore(now)) {
                        jdbc.update("UPDATE user_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(now),
                                rs.getLong("id"));
                        log.warn("Old refresh token of user {} was used again: session {} ended",
                                rs.getLong("user_id"), rs.getLong("id"));
                    }
                }, hash);
    }

    /** Sign out on this device. */
    public void end(String token) {
        jdbc.update("UPDATE user_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
                Timestamp.from(clock.instant()), hash(token));
    }

    /** Sign out everywhere (new password). */
    public void endAll(long userId) {
        jdbc.update("UPDATE user_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                Timestamp.from(clock.instant()), userId);
    }

    // Ended and expired sessions are kept a day for the logs, then removed (AuthCleanupJob)
    public int deleteOld() {
        Timestamp dayAgo = Timestamp.from(clock.instant().minus(Duration.ofDays(1)));
        return jdbc.update("DELETE FROM user_sessions WHERE expires_at < ? OR revoked_at < ?", dayAgo, dayAgo);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new Row(rs.getLong("id"), rs.getLong("user_id"), rs.getTimestamp("expires_at").toInstant(),
                revoked == null ? null : revoked.toInstant());
    }
}

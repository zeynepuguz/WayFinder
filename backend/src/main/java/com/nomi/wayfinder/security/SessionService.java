package com.nomi.wayfinder.security;

import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signed-in devices. The app holds a short JWT (JWT_EXPIRATION_MINUTES) and a refresh token; with the refresh token
 * it gets a new pair, which moves the session's end 7 days ahead. So a user who opens the app at least once a week
 * stays signed in, one who does not signs in again.
 * <p>
 * Every refresh replaces the refresh token. The previous one is remembered: if it comes back later than a minute
 * (two tabs refreshing at the same moment), it was copied by someone else and the session is ended.
 * <p>
 * The JWT carries its session id ("sid"). An ended session is remembered here for one JWT lifetime, so its access
 * token is refused at once (SecurityConfig), not only when it runs out.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    public static final Duration IDLE_TIMEOUT = Duration.ofDays(7);
    // Two tabs / requests that refresh with the same token at nearly the same time are not an attack
    static final Duration REUSE_GRACE = Duration.ofMinutes(1);
    public static final String SIGN_IN_AGAIN = "Session expired, sign in again";

    /** Where a sign-in comes from: the User-Agent header and the client's IP address. */
    public record Client(String userAgent, String ip) {

        public String device() {
            return UserAgents.describe(userAgent);
        }
    }

    public record Issued(long sessionId, long userId, String refreshToken, Instant expiresAt) {
    }

    /** An open session in the profile; current = the one this request came with. */
    public record SessionView(long id, String device, String ipAddress, Instant createdAt, Instant lastUsedAt,
                              boolean current) {
    }

    private record Row(long id, long userId, Instant expiresAt, Instant revokedAt) {
    }

    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration accessTokenLifetime;
    // Session id -> when it ended, kept for one access token lifetime
    private final Map<Long, Instant> ended = new ConcurrentHashMap<>();

    public SessionService(JdbcTemplate jdbc, Clock clock, NomiProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        long minutes = properties.security() == null ? 15 : properties.security().jwtExpirationMinutes();
        this.accessTokenLifetime = Duration.ofMinutes(Math.max(1, minutes));
    }

    // After a restart: sessions ended within the last access token lifetime are still refused
    @EventListener(ApplicationReadyEvent.class)
    public void loadRecentlyEnded() {
        try {
            jdbc.query("SELECT id, revoked_at FROM user_sessions WHERE revoked_at > ?",
                    rs -> {
                        ended.put(rs.getLong("id"), rs.getTimestamp("revoked_at").toInstant());
                    },
                    Timestamp.from(clock.instant().minus(accessTokenLifetime)));
        } catch (RuntimeException e) {
            log.warn("Recently ended sessions not loaded: {}", e.getMessage());
        }
    }

    public Issued start(long userId, Client client) {
        Instant now = clock.instant();
        String token = newToken();
        Instant expiresAt = now.plus(IDLE_TIMEOUT);
        String userAgent = client.userAgent();
        Long id = jdbc.queryForObject("""
                INSERT INTO user_sessions (user_id, token_hash, created_at, last_used_at, expires_at, user_agent, device,
                                           ip_address)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                userId, hash(token), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt),
                userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())),
                client.device(), client.ip() == null ? null : client.ip().substring(0, Math.min(45, client.ip().length())));
        return new Issued(id, userId, token, expiresAt);
    }

    /** False for a user's first sign-in and for a device seen in their sessions (kept up to 8 days after use). */
    public boolean isNewDevice(long userId, String device) {
        Boolean anySession = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM user_sessions WHERE user_id = ?)",
                Boolean.class, userId);
        if (!Boolean.TRUE.equals(anySession)) {
            return false;
        }
        Boolean seen = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_sessions WHERE user_id = ? AND device IS NOT DISTINCT FROM ?)""",
                Boolean.class, userId, device);
        return !Boolean.TRUE.equals(seen);
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
        return new Issued(session.id(), session.userId(), next, expiresAt);
    }

    // An already replaced token came back: within the grace minute it is a parallel refresh, later a stolen copy
    private void reuse(String hash, Instant now) {
        jdbc.query("""
                SELECT id, user_id, replaced_at FROM user_sessions WHERE previous_hash = ? AND revoked_at IS NULL""",
                rs -> {
                    Instant replacedAt = rs.getTimestamp("replaced_at").toInstant();
                    if (replacedAt.plus(REUSE_GRACE).isBefore(now)) {
                        long id = rs.getLong("id");
                        jdbc.update("UPDATE user_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(now), id);
                        markEnded(List.of(id), now);
                        log.warn("Old refresh token of user {} was used again: session {} ended",
                                rs.getLong("user_id"), id);
                    }
                }, hash);
    }

    /** The user's open sessions, most recently used first. */
    public List<SessionView> list(long userId, Long currentSessionId) {
        return jdbc.query("""
                SELECT id, device, ip_address, created_at, last_used_at FROM user_sessions
                WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ? ORDER BY last_used_at DESC""",
                (rs, i) -> new SessionView(rs.getLong("id"), rs.getString("device"), rs.getString("ip_address"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_used_at").toInstant(),
                        currentSessionId != null && rs.getLong("id") == currentSessionId),
                userId, Timestamp.from(clock.instant()));
    }

    /** Sign out on this device (its refresh token). */
    public void end(String token) {
        endWhere("token_hash = ?", hash(token));
    }

    /** One session of the user (profile: "Çıkış yap" next to a device); false when it is not theirs or not open. */
    public boolean endOne(long userId, long sessionId) {
        return !endWhere("id = ? AND user_id = ?", sessionId, userId).isEmpty();
    }

    /** Every session of the user but the current one; how many were ended. */
    public int endOthers(long userId, Long keepSessionId) {
        return endWhere("user_id = ? AND id <> ?", userId, keepSessionId == null ? -1L : keepSessionId).size();
    }

    /** Sign out everywhere (new password). */
    public void endAll(long userId) {
        endWhere("user_id = ?", userId);
    }

    /** True while an ended session's access tokens could still be valid: they are refused. */
    public boolean isEnded(long sessionId) {
        Instant at = ended.get(sessionId);
        if (at == null) {
            return false;
        }
        if (at.plus(accessTokenLifetime).isBefore(clock.instant())) {
            ended.remove(sessionId);
            return false;
        }
        return true;
    }

    private List<Long> endWhere(String condition, Object... args) {
        Instant now = clock.instant();
        Object[] params = new Object[args.length + 1];
        params[0] = Timestamp.from(now);
        System.arraycopy(args, 0, params, 1, args.length);
        List<Long> ids = jdbc.queryForList(
                "UPDATE user_sessions SET revoked_at = ? WHERE " + condition + " AND revoked_at IS NULL RETURNING id",
                Long.class, params);
        markEnded(ids, now);
        return ids;
    }

    private void markEnded(List<Long> ids, Instant at) {
        ids.forEach(id -> ended.put(id, at));
        // Old entries cannot be valid any more; dropped so the map stays small
        Instant cutoff = at.minus(accessTokenLifetime);
        ended.values().removeIf(t -> t.isBefore(cutoff));
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

package com.nomi.wayfinder.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * The 6-digit codes that prove the user owns the e-mail: at sign-up and at every sign-in.
 * - Only a hash of the code is stored; a code works once, for 10 minutes, with 5 guesses.
 * - A new code can be requested once a minute; it replaces the previous one of the same purpose.
 */
@Service
public class EmailCodeService {

    public enum Purpose { SIGN_UP, SIGN_IN }

    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    public static final Duration RESEND_AFTER = Duration.ofMinutes(1);
    static final int MAX_ATTEMPTS = 5;

    private record Code(long id, String hash, Instant createdAt, Instant expiresAt, int attempts, Instant usedAt) {
    }

    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public EmailCodeService(JdbcTemplate jdbc, PasswordEncoder encoder, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.clock = clock;
    }

    /**
     * A new code for the user, handed to mail (which sends it). Nothing happens when one was sent less than a
     * minute ago: the user already has a fresh code.
     */
    public void send(long userId, Purpose purpose, Consumer<String> mail) {
        Instant now = clock.instant();
        Code latest = latest(userId, purpose);
        if (latest != null && latest.usedAt() == null && latest.createdAt().plus(RESEND_AFTER).isAfter(now)) {
            return;
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        jdbc.update("DELETE FROM email_codes WHERE user_id = ? AND purpose = ?", userId, purpose.name());
        jdbc.update("""
                INSERT INTO email_codes (user_id, purpose, code_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)""",
                userId, purpose.name(), encoder.encode(code), Timestamp.from(now.plus(CODE_TTL)), Timestamp.from(now));
        mail.accept(code);
    }

    /** True when a code of this purpose was sent within the given time (resend is only for a running sign-in). */
    public boolean sentWithin(long userId, Purpose purpose, Duration within) {
        Code latest = latest(userId, purpose);
        return latest != null && latest.usedAt() == null && latest.createdAt().plus(within).isAfter(clock.instant());
    }

    /** Checks the code and uses it up; a wrong guess is counted. Callers must not roll the count back. */
    public boolean verify(long userId, Purpose purpose, String code) {
        Instant now = clock.instant();
        Code latest = latest(userId, purpose);
        if (latest == null || latest.usedAt() != null || !latest.expiresAt().isAfter(now)
                || latest.attempts() >= MAX_ATTEMPTS) {
            return false;
        }
        if (!encoder.matches(code.trim(), latest.hash())) {
            jdbc.update("UPDATE email_codes SET attempts = attempts + 1 WHERE id = ?", latest.id());
            return false;
        }
        return jdbc.update("UPDATE email_codes SET used_at = ? WHERE id = ? AND used_at IS NULL",
                Timestamp.from(now), latest.id()) == 1;
    }

    private Code latest(long userId, Purpose purpose) {
        List<Code> codes = jdbc.query("""
                SELECT id, code_hash, created_at, expires_at, attempts, used_at FROM email_codes
                WHERE user_id = ? AND purpose = ? ORDER BY created_at DESC LIMIT 1""",
                (rs, i) -> new Code(rs.getLong("id"), rs.getString("code_hash"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("attempts"),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant()),
                userId, purpose.name());
        return codes.isEmpty() ? null : codes.getFirst();
    }
}

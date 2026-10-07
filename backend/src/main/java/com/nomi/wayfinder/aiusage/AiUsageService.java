package com.nomi.wayfinder.aiusage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Writes down every AI service call (headers X-AI-Model / X-AI-Input-Tokens / X-AI-Output-Tokens from ai-service)
 * and sums them for the admin area. Recording never fails a user request: a database error is only logged.
 */
@Service
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

    // The budget check runs on every assistant message; the month's sum is read again at most this often
    static final Duration BUDGET_CACHE = Duration.ofMinutes(1);
    static final int TOP_USERS = 10;
    static final int DAYS = 30;

    public enum Kind { INTENT, PHOTO, OTHER }

    public record Totals(long calls, long failures, long inputTokens, long outputTokens, double costUsd) {
    }

    public record Day(LocalDate day, long calls, double costUsd) {
    }

    // email is null for photo checks (they run in the background) and deleted accounts
    public record TopUser(Long userId, String email, long calls, double costUsd) {
    }

    public record Summary(Totals today, Totals month, double monthlyBudgetUsd, boolean budgetReached,
                          List<Day> days, List<TopUser> topUsersMonth) {
    }

    private final JdbcTemplate jdbc;
    private final AiUsageProperties properties;
    private final Clock clock;

    private volatile Instant budgetCheckedAt = Instant.EPOCH;
    private volatile boolean budgetReached;

    public AiUsageService(JdbcTemplate jdbc, AiUsageProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public void record(Kind kind, Long userId, HttpHeaders headers, boolean ok, long millis) {
        String model = headers.getFirst("X-AI-Model");
        int input = intHeader(headers, "X-AI-Input-Tokens");
        int output = intHeader(headers, "X-AI-Output-Tokens");
        try {
            jdbc.update("""
                    INSERT INTO ai_usage (user_id, kind, model, input_tokens, output_tokens, cost_usd, ok, millis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    userId, kind.name(), model, input, output, properties.cost(input, output), ok,
                    (int) Math.min(millis, Integer.MAX_VALUE));
        } catch (RuntimeException e) {
            log.warn("AI usage not recorded ({} {} tokens): {}", kind, input + output, e.getMessage());
        }
    }

    /** True once this month's estimated cost reached AI_MONTHLY_BUDGET_USD (checked at most once a minute). */
    public boolean budgetReached() {
        if (properties.monthlyBudgetUsd() <= 0) {
            return false;
        }
        Instant now = clock.instant();
        if (Duration.between(budgetCheckedAt, now).compareTo(BUDGET_CACHE) >= 0) {
            try {
                boolean reached = totals(monthStart()).costUsd() >= properties.monthlyBudgetUsd();
                if (reached && !budgetReached) {
                    log.warn("Monthly AI budget of {} USD reached: rule-based parser only until next month",
                            properties.monthlyBudgetUsd());
                }
                budgetReached = reached;
            } catch (RuntimeException e) {
                // Fail open like the daily quota; OpenAI's own project limit is the hard backstop
                log.warn("AI budget check skipped: {}", e.getMessage());
            }
            budgetCheckedAt = now;
        }
        return budgetReached;
    }

    public Summary summary() {
        Instant monthStart = monthStart();
        List<Day> days = jdbc.query("""
                SELECT (created_at AT TIME ZONE ?)::date AS day, count(*) AS calls, coalesce(sum(cost_usd), 0) AS cost
                FROM ai_usage WHERE created_at >= ?
                GROUP BY 1 ORDER BY 1""",
                (rs, i) -> new Day(rs.getObject("day", LocalDate.class), rs.getLong("calls"), rs.getDouble("cost")),
                zone().getId(), Timestamp.from(dayStart(today().minusDays(DAYS - 1))));
        List<TopUser> top = jdbc.query("""
                SELECT a.user_id, u.email, count(*) AS calls, coalesce(sum(a.cost_usd), 0) AS cost
                FROM ai_usage a LEFT JOIN users u ON u.id = a.user_id
                WHERE a.created_at >= ? AND a.user_id IS NOT NULL
                GROUP BY a.user_id, u.email ORDER BY cost DESC, calls DESC LIMIT ?""",
                (rs, i) -> new TopUser(rs.getLong("user_id"), rs.getString("email"), rs.getLong("calls"),
                        rs.getDouble("cost")),
                Timestamp.from(monthStart), TOP_USERS);
        Totals month = totals(monthStart);
        boolean reached = properties.monthlyBudgetUsd() > 0 && month.costUsd() >= properties.monthlyBudgetUsd();
        return new Summary(totals(dayStart(today())), month, properties.monthlyBudgetUsd(), reached, days, top);
    }

    private Totals totals(Instant from) {
        return jdbc.queryForObject("""
                SELECT count(*) AS calls, count(*) FILTER (WHERE NOT ok) AS failures,
                       coalesce(sum(input_tokens), 0) AS input, coalesce(sum(output_tokens), 0) AS output,
                       coalesce(sum(cost_usd), 0) AS cost
                FROM ai_usage WHERE created_at >= ?""",
                (rs, i) -> new Totals(rs.getLong("calls"), rs.getLong("failures"), rs.getLong("input"),
                        rs.getLong("output"), rs.getDouble("cost")),
                Timestamp.from(from));
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private Instant dayStart(LocalDate day) {
        return day.atStartOfDay(zone()).toInstant();
    }

    private Instant monthStart() {
        return dayStart(today().withDayOfMonth(1));
    }

    static int intHeader(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

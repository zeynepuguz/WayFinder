package com.nomi.wayfinder.admin;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.osm.PlacesChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The owner's admin area: places they remove (gone for users at once, listed under the city's removed places) or
 * mark as suspect (shown with "may have closed", never suggested), users' "I think this place is closed" reports,
 * and users' suggestions for the app (refused when they swear, ProfanityFilter).
 */
@Service
public class PlaceReviewService {

    public enum Action { REMOVE, SUSPECT, CLEAR }

    public enum ReportReason { CLOSED, WRONG_LOCATION }

    /**
     * @param reports open reports of the place (0 in the removed / suspect lists)
     */
    public record ReviewedPlace(long id, String name, String category, String district, String city, String review,
                                Instant reviewedAt, int reports, String lastReason, Instant lastReportAt) {
    }

    public record Feedback(long id, String message, String userEmail, Instant createdAt, boolean read) {
    }

    public record Counts(int openReports, int suspects, int removed, int unreadFeedback) {
    }

    static final String REFUSED_MESSAGE = "Mesajın uygunsuz ifadeler içerdiği için gönderilmedi.";

    // A place with its district and city names
    private static final String PLACE_COLUMNS = """
            p.id, p.name, p.category, d.name AS district_name, c.name AS city_name, p.review, p.reviewed_at
            """;
    private static final String PLACE_JOINS = """
            LEFT JOIN districts d ON d.id = p.district_id LEFT JOIN cities c ON c.id = p.city_id
            """;

    private final JdbcTemplate jdbc;
    private final CityService cityService;
    private final ApplicationEventPublisher events;

    public PlaceReviewService(JdbcTemplate jdbc, CityService cityService, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.cityService = cityService;
        this.events = events;
    }

    // ---------- the owner's review of a place ----------

    @Transactional
    public void review(long placeId, Action action) {
        int changed = switch (action) {
            case REMOVE -> jdbc.update("""
                    UPDATE places SET review = 'REMOVED', hidden = TRUE, reviewed_at = now(), updated_at = now()
                    WHERE id = ?""", placeId);
            case SUSPECT -> jdbc.update("""
                    UPDATE places SET review = 'SUSPECT', hidden = FALSE, reviewed_at = now(), updated_at = now()
                    WHERE id = ?""", placeId);
            // Back to normal (also "geri al" in the removed places)
            case CLEAR -> jdbc.update("""
                    UPDATE places SET review = NULL, hidden = FALSE, reviewed_at = now(), updated_at = now()
                    WHERE id = ?""", placeId);
        };
        if (changed == 0) {
            throw new ResourceNotFoundException("Place not found: " + placeId);
        }
        // A reviewed place's reports are dealt with
        jdbc.update("UPDATE place_reports SET resolved_at = now() WHERE place_id = ? AND resolved_at IS NULL", placeId);
        events.publishEvent(new PlacesChangedEvent("owner review"));
    }

    /**
     * @param review REMOVED or SUSPECT
     * @param city   city slug; null = all cities
     */
    public List<ReviewedPlace> reviewed(String review, String city) {
        Long cityId = city == null || city.isBlank() ? null : cityService.findBySlug(city)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + city)).id();
        return jdbc.query("SELECT " + PLACE_COLUMNS + " FROM places p " + PLACE_JOINS + """
                        WHERE p.review = ? AND (CAST(? AS bigint) IS NULL OR p.city_id = ?)
                        ORDER BY p.reviewed_at DESC NULLS LAST
                        LIMIT 500
                        """,
                (rs, i) -> new ReviewedPlace(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
                        rs.getString("district_name"), rs.getString("city_name"), rs.getString("review"),
                        ts(rs.getTimestamp("reviewed_at")), 0, null, null),
                review, cityId, cityId);
    }

    // ---------- users' reports ----------

    @Transactional
    public void report(long userId, long placeId, ReportReason reason) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM places WHERE id = ? AND NOT hidden", Integer.class,
                placeId);
        if (exists == null || exists == 0) {
            throw new ResourceNotFoundException("Place not found: " + placeId);
        }
        // One report per user, place and reason (a second tap counts once)
        jdbc.update("""
                INSERT INTO place_reports (place_id, user_id, reason) VALUES (?, ?, ?)
                ON CONFLICT (place_id, user_id, reason) DO UPDATE SET created_at = now(), resolved_at = NULL
                """, placeId, userId, reason.name());
    }

    // Open reports, one row per place, most reported first
    public List<ReviewedPlace> openReports() {
        return jdbc.query("SELECT " + PLACE_COLUMNS + """
                        , count(*) AS reports, (array_agg(r.reason ORDER BY r.created_at DESC))[1] AS last_reason,
                          max(r.created_at) AS last_report
                        FROM place_reports r JOIN places p ON p.id = r.place_id
                        """ + PLACE_JOINS + """
                        WHERE r.resolved_at IS NULL
                        GROUP BY p.id, p.name, p.category, d.name, c.name, p.review, p.reviewed_at
                        ORDER BY count(*) DESC, max(r.created_at) DESC
                        LIMIT 500
                        """,
                (rs, i) -> new ReviewedPlace(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
                        rs.getString("district_name"), rs.getString("city_name"), rs.getString("review"),
                        ts(rs.getTimestamp("reviewed_at")), rs.getInt("reports"), rs.getString("last_reason"),
                        ts(rs.getTimestamp("last_report"))));
    }

    // "Bildirimi kapat": the place stays as it is
    @Transactional
    public void dismissReports(long placeId) {
        jdbc.update("UPDATE place_reports SET resolved_at = now() WHERE place_id = ? AND resolved_at IS NULL", placeId);
    }

    // ---------- suggestions for the app ----------

    @Transactional
    public void feedback(Long userId, String message) {
        String text = message == null ? "" : message.strip();
        if (text.length() < 3) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Mesaj çok kısa.");
        }
        if (ProfanityFilter.containsProfanity(text)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, REFUSED_MESSAGE);
        }
        jdbc.update("INSERT INTO app_feedback (user_id, message) VALUES (?, ?)", userId, text);
    }

    public List<Feedback> feedbackList() {
        return jdbc.query("""
                        SELECT f.id, f.message, u.email, f.created_at, f.read_at IS NOT NULL AS read
                        FROM app_feedback f LEFT JOIN users u ON u.id = f.user_id
                        ORDER BY f.created_at DESC LIMIT 500
                        """,
                (rs, i) -> new Feedback(rs.getLong("id"), rs.getString("message"), rs.getString("email"),
                        ts(rs.getTimestamp("created_at")), rs.getBoolean("read")));
    }

    @Transactional
    public void markFeedbackRead(long id) {
        jdbc.update("UPDATE app_feedback SET read_at = now() WHERE id = ? AND read_at IS NULL", id);
    }

    public Counts counts() {
        return jdbc.queryForObject("""
                        SELECT (SELECT count(DISTINCT place_id) FROM place_reports WHERE resolved_at IS NULL) AS reports,
                               (SELECT count(*) FROM places WHERE review = 'SUSPECT') AS suspects,
                               (SELECT count(*) FROM places WHERE review = 'REMOVED') AS removed,
                               (SELECT count(*) FROM app_feedback WHERE read_at IS NULL) AS feedback
                        """,
                (rs, i) -> new Counts(rs.getInt("reports"), rs.getInt("suspects"), rs.getInt("removed"),
                        rs.getInt("feedback")));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

package com.nomi.wayfinder.service;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.PlaceNotFoundException;
import com.nomi.wayfinder.planning.CommunityScore;
import com.nomi.wayfinder.planning.RatingReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Nomi's users think of places: star ratings of food and drink places (one per user per place) and how many
 * users saved a place. Both are summed on the place row (user_rating_sum / _count, favorite_count), which lists and
 * the route planner read (planning/CommunityScore).
 * <p>
 * Against a place's owner pushing it up: one rating per account (accounts need a proven e-mail), a daily limit per
 * account, and the Bayesian average in CommunityScore, where a few votes move a place only a little.
 */
@Service
public class PlaceCommunityService {

    private static final Logger log = LoggerFactory.getLogger(PlaceCommunityService.class);

    // Yeme içme: sights are ranked by how well-known they are (popularity), not by stars
    public static final Set<PlaceCategory> RATEABLE = EnumSet.of(PlaceCategory.BREAKFAST, PlaceCategory.RESTAURANT,
            PlaceCategory.CAFE, PlaceCategory.DESSERT);
    static final int DAILY_RATINGS_PER_USER = 40;

    /**
     * @param average      the users' plain average (one decimal); null without votes
     * @param mine         the asking user's stars; null = not rated (or not signed in)
     * @param rateable     false for sights, markets etc.: no stars to give
     * @param myReasons    why the asking user gave their stars (empty when not answered)
     * @param topReasons   what most raters said, from {@link #REASON_SHOWN_FROM} users on (one user's complaint is
     *                     not shown as what people think)
     */
    public record RatingView(Double average, int count, Integer mine, int favoriteCount, boolean rateable,
                             List<RatingReason> myReasons, List<ReasonCount> topReasons) {
    }

    public record ReasonCount(RatingReason reason, int count, boolean positive) {
    }

    static final int REASON_SHOWN_FROM = 2;
    static final int TOP_REASONS = 6;

    private record PlaceRow(String category, int sum, int count, int favorites) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PlaceCommunityService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public RatingView rate(long userId, long placeId, int stars) {
        PlaceRow place = visiblePlace(placeId);
        if (!rateable(place.category())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Only food and drink places can be rated");
        }
        Timestamp dayAgo = Timestamp.from(clock.instant().minus(Duration.ofDays(1)));
        Integer today = jdbc.queryForObject(
                "SELECT count(*) FROM place_ratings WHERE user_id = ? AND updated_at > ? AND place_id <> ?",
                Integer.class, userId, dayAgo, placeId);
        if (today != null && today >= DAILY_RATINGS_PER_USER) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "Too many ratings today, try again tomorrow");
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO place_ratings (place_id, user_id, stars, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (place_id, user_id) DO UPDATE SET stars = EXCLUDED.stars, updated_at = EXCLUDED.updated_at""",
                placeId, userId, stars, now, now);
        // New stars: answers that no longer fit go ("Pahalı" does not stay on a 5-star rating)
        List<String> unfit = Arrays.stream(RatingReason.values()).filter(r -> !r.fits(stars)).map(Enum::name).toList();
        jdbc.update("DELETE FROM place_rating_reasons WHERE place_id = ? AND user_id = ? AND reason = ANY (?)",
                placeId, userId, unfit.toArray(String[]::new));
        refreshRatings(placeId);
        refreshCommunityTags(placeId);
        return view(placeId, userId);
    }

    /** "Neden bu puanı verdin?": replaces the user's answers for their rating of the place. */
    @Transactional
    public RatingView setReasons(long userId, long placeId, List<RatingReason> reasons) {
        visiblePlace(placeId);
        Integer stars = myStars(placeId, userId);
        if (stars == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Rate the place first");
        }
        List<RatingReason> chosen = reasons == null ? List.of() : reasons.stream().distinct().toList();
        if (chosen.size() > RatingReason.MAX_PER_RATING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "At most " + RatingReason.MAX_PER_RATING + " answers");
        }
        if (chosen.stream().anyMatch(r -> !r.fits(stars))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "These answers do not fit " + stars + " stars");
        }
        jdbc.update("DELETE FROM place_rating_reasons WHERE place_id = ? AND user_id = ?", placeId, userId);
        for (RatingReason reason : chosen) {
            jdbc.update("INSERT INTO place_rating_reasons (place_id, user_id, reason) VALUES (?, ?, ?)",
                    placeId, userId, reason.name());
        }
        refreshCommunityTags(placeId);
        return view(placeId, userId);
    }

    @Transactional
    public RatingView removeRating(long userId, long placeId) {
        visiblePlace(placeId);
        // The answers go with it (ON DELETE CASCADE)
        jdbc.update("DELETE FROM place_ratings WHERE place_id = ? AND user_id = ?", placeId, userId);
        refreshRatings(placeId);
        refreshCommunityTags(placeId);
        return view(placeId, userId);
    }

    @Transactional(readOnly = true)
    public RatingView view(long placeId, Long userId) {
        PlaceRow place = visiblePlace(placeId);
        Integer mine = userId == null ? null : myStars(placeId, userId);
        List<RatingReason> myReasons = mine == null ? List.of() : jdbc.queryForList(
                "SELECT reason FROM place_rating_reasons WHERE place_id = ? AND user_id = ?", String.class,
                placeId, userId).stream().map(RatingReason::valueOf).sorted().toList();
        List<ReasonCount> top = reasonVotes(placeId).entrySet().stream()
                .filter(e -> e.getValue() >= REASON_SHOWN_FROM)
                .sorted(Map.Entry.<RatingReason, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_REASONS)
                .map(e -> new ReasonCount(e.getKey(), e.getValue(), e.getKey().positive()))
                .toList();
        return new RatingView(CommunityScore.average(place.sum(), place.count()), place.count(), mine,
                place.favorites(), rateable(place.category()), myReasons, top);
    }

    private Integer myStars(long placeId, long userId) {
        return jdbc.query("SELECT stars FROM place_ratings WHERE place_id = ? AND user_id = ?",
                rs -> rs.next() ? (Integer) rs.getInt("stars") : null, placeId, userId);
    }

    private Map<RatingReason, Integer> reasonVotes(long placeId) {
        Map<RatingReason, Integer> votes = new EnumMap<>(RatingReason.class);
        jdbc.query("SELECT reason, count(*) AS users FROM place_rating_reasons WHERE place_id = ? GROUP BY reason",
                rs -> {
                    votes.put(RatingReason.valueOf(rs.getString("reason")), rs.getInt("users"));
                }, placeId);
        return votes;
    }

    // What enough of the place's raters agree on becomes its community tags (planning/RatingReason.communityTags)
    private void refreshCommunityTags(long placeId) {
        Integer raters = jdbc.queryForObject("SELECT count(*) FROM place_ratings WHERE place_id = ?", Integer.class,
                placeId);
        String[] tags = RatingReason.communityTags(reasonVotes(placeId), raters == null ? 0 : raters).stream()
                .sorted().toArray(String[]::new);
        jdbc.update("UPDATE places SET community_tags = ? WHERE id = ?", tags, placeId);
    }

    /**
     * "Favori mekanlar" of a district, a city (districtId null) or all Türkiye (both null): places users saved, or
     * rated 4 or better on average; most saved first, then the users' rating (CommunityScore, in SQL).
     */
    @Transactional(readOnly = true)
    public List<Long> favoritePlaceIds(Long cityId, Long districtId, int limit) {
        String scope = districtId != null ? " AND district_id = ?" : cityId != null ? " AND city_id = ?" : "";
        List<Object> args = new ArrayList<>();
        if (districtId != null) {
            args.add(districtId);
        } else if (cityId != null) {
            args.add(cityId);
        }
        args.add(limit);
        return jdbc.queryForList("""
                SELECT id FROM places
                WHERE hidden = FALSE AND review IS NULL AND unconfirmed = FALSE
                  AND (favorite_count > 0 OR (user_rating_count > 0 AND user_rating_sum >= 4 * user_rating_count))"""
                + scope + """

                ORDER BY favorite_count DESC,
                         (coalesce(rating, 3.8) * 5 + user_rating_sum) / (5.0 + user_rating_count) DESC, id
                LIMIT ?""", Long.class, args.toArray());
    }

    /** The places the user saved: the planner prefers them (their own favourites). */
    @Transactional(readOnly = true)
    public Set<Long> savedPlaceIds(long userId) {
        return new HashSet<>(jdbc.queryForList("SELECT place_id FROM saved_places WHERE user_id = ?", Long.class,
                userId));
    }

    /** After a save / unsave: the place's favorite_count. */
    public void refreshSaves(long placeId) {
        jdbc.update("""
                UPDATE places SET favorite_count = (SELECT count(*) FROM saved_places WHERE place_id = ?)
                WHERE id = ?""", placeId, placeId);
    }

    private void refreshRatings(long placeId) {
        jdbc.update("""
                UPDATE places p SET user_rating_sum = r.total, user_rating_count = r.votes
                FROM (SELECT coalesce(sum(stars), 0) AS total, count(*) AS votes FROM place_ratings WHERE place_id = ?) r
                WHERE p.id = ?""", placeId, placeId);
    }

    /**
     * Nightly: the sums are recounted from the rows. They drift when an account is deleted (its ratings and saved
     * places cascade away without passing through here).
     */
    @Scheduled(cron = "${nomi.places.community-recount-cron:0 45 4 * * *}", zone = "${nomi.timezone:Europe/Istanbul}")
    public void recountAll() {
        try {
            int ratings = jdbc.update("""
                    UPDATE places p SET user_rating_sum = coalesce(r.total, 0), user_rating_count = coalesce(r.votes, 0)
                    FROM places q LEFT JOIN (SELECT place_id, sum(stars) AS total, count(*) AS votes
                                             FROM place_ratings GROUP BY place_id) r ON r.place_id = q.id
                    WHERE p.id = q.id AND (p.user_rating_count > 0 OR r.votes IS NOT NULL)
                      AND (p.user_rating_sum <> coalesce(r.total, 0) OR p.user_rating_count <> coalesce(r.votes, 0))""");
            int saves = jdbc.update("""
                    UPDATE places p SET favorite_count = coalesce(s.saved, 0)
                    FROM places q LEFT JOIN (SELECT place_id, count(*) AS saved FROM saved_places GROUP BY place_id) s
                         ON s.place_id = q.id
                    WHERE p.id = q.id AND (p.favorite_count > 0 OR s.saved IS NOT NULL)
                      AND p.favorite_count <> coalesce(s.saved, 0)""");
            if (ratings > 0 || saves > 0) {
                log.info("Community recount: ratings of {} place(s), saves of {} place(s) corrected", ratings, saves);
            }
            // Community tags of every place with answers or tags (few: only rated places have any)
            jdbc.queryForList("""
                    SELECT DISTINCT place_id FROM place_rating_reasons
                    UNION SELECT id FROM places WHERE cardinality(community_tags) > 0""", Long.class)
                    .forEach(this::refreshCommunityTags);
        } catch (Exception e) {
            log.warn("Community recount failed, retried tomorrow: {}", e.getMessage());
        }
    }

    private static boolean rateable(String category) {
        return RATEABLE.stream().anyMatch(c -> c.name().equals(category));
    }

    // A place users can see (hidden / removed ones answer 404 like everywhere else)
    private PlaceRow visiblePlace(long placeId) {
        List<PlaceRow> rows = jdbc.query("""
                SELECT category, user_rating_sum, user_rating_count, favorite_count FROM places
                WHERE id = ? AND hidden = FALSE""",
                (rs, i) -> new PlaceRow(rs.getString("category"), rs.getInt("user_rating_sum"),
                        rs.getInt("user_rating_count"), rs.getInt("favorite_count")),
                placeId);
        if (rows.isEmpty()) {
            throw new PlaceNotFoundException(placeId);
        }
        return rows.getFirst();
    }
}
